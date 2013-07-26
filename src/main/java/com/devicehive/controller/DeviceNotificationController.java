package com.devicehive.controller;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.SecurityContext;

import com.devicehive.auth.HivePrincipal;
import com.devicehive.dao.DeviceDAO;
import com.devicehive.dao.DeviceNotificationDAO;
import com.devicehive.json.strategies.JsonPolicyApply;
import com.devicehive.json.strategies.JsonPolicyDef;
import com.devicehive.json.strategies.JsonPolicyDef.Policy;
import com.devicehive.messages.util.Params;
import com.devicehive.messages.MessageDetails;
import com.devicehive.messages.MessageType;
import com.devicehive.messages.bus.DeferredResponse;
import com.devicehive.messages.bus.LocalMessageBus;
import com.devicehive.messages.bus.MessageBus;
import com.devicehive.messages.util.Params;
import com.devicehive.model.Device;
import com.devicehive.model.DeviceNotification;
import com.devicehive.model.User;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 
 * REST controller for device notifications: <i>/device/{deviceGuid}/notification</i> and <i>/device/notification</i>.
 * See <a href="http://www.devicehive.com/restful#Reference/DeviceNotification">DeviceHive RESTful API: DeviceNotification</a> for details.
 * 
 * @author rroschin
 */
@Path("/device")
public class DeviceNotificationController {

    @Inject
    private DeviceNotificationDAO notificationDAO;
    @Inject
    private DeviceDAO deviceDAO;
    @Inject
    private MessageBus messageBus;

    @GET
    @Path("/{deviceGuid}/notification")
    @RolesAllowed({"CLIENT", "ADMIN"})
    @Produces(MediaType.APPLICATION_JSON)
    @JsonPolicyApply(JsonPolicyDef.Policy.NOTIFICATION_TO_CLIENT)
    public List<DeviceNotification> query(@PathParam("deviceGuid") String guid,
                                          @QueryParam("start") String start,
                                          @QueryParam("end") String end,
                                          @QueryParam("notification") String notification,
                                          @QueryParam("sortField") String sortField,
                                          @QueryParam("sortOrder") String sortOrder,
                                          @QueryParam("take") Integer take,
                                          @QueryParam("skip") Integer skip) {
        if (sortOrder != null && !sortOrder.equals("DESC") && !sortOrder.equals("ASC")) {
            throw new BadRequestException("The sort order cannot be equal " + sortOrder);
        }
        boolean sortOrderAsc = true;
        if ("DESC".equals(sortOrder)) {
            sortOrderAsc = false;
        }
        if (!"Timestamp".equals(sortField) && !"Notification".equals(sortField) && sortField != null) {
            throw new BadRequestException("The sort field cannot be equal " + sortField);
        }
        if (sortField == null) {
            sortField = "timestamp";
        }
        sortField = sortField.toLowerCase();

        Date startTimestamp = null, endTimestamp = null;

        if (start != null) {
            startTimestamp = Params.parseUTCDate(start);
            if (startTimestamp == null) {
                throw new BadRequestException("unparseable date " + start);
            }
        }
        if (end != null) {
            endTimestamp = Params.parseUTCDate(end);
            if (endTimestamp == null) {
                throw new BadRequestException("unparseable date " + end);
            }
        }

        Device device = getDevice(guid);
        return notificationDAO.queryDeviceNotification(device, startTimestamp, endTimestamp, notification, sortField, sortOrderAsc, take, skip);
    }

    @GET
    @Path("/{deviceGuid}/notification/{id}")
    @RolesAllowed({"CLIENT", "ADMIN"})
    @Produces(MediaType.APPLICATION_JSON)
    @JsonPolicyApply(JsonPolicyDef.Policy.NOTIFICATION_TO_CLIENT)
    public DeviceNotification get(@PathParam("deviceGuid") String guid, @PathParam("id") Long notificationId) {
        DeviceNotification deviceNotification = notificationDAO.findById(notificationId);
        String deviceGuidFromNotification = deviceNotification.getDevice().getGuid().toString();
        if (!deviceGuidFromNotification.equals(guid)) {
            throw new NotFoundException("Notification with id: " + notificationId + " associated with device: " + guid + " not found");
        }
        return deviceNotification;
    }

    private Device getDevice(String uuid) {
        UUID deviceId;
        try {
            deviceId = UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("unparseable guid: " + uuid);
        }
        Device device = deviceDAO.findByUUID(deviceId);
        if (device == null) {
            throw new NotFoundException("device with guid " + uuid + " not found");
        }
        return device;
    }

    /**
     * 
     * Implementation of <a href="http://www.devicehive.com/restful#Reference/DeviceNotification/poll">DeviceHive RESTful API: DeviceNotification: poll</a>
     * 
     * @param deviceGuid Device unique identifier.
     * @param timestampUTC Timestamp of the last received command (UTC). If not specified, the server's timestamp is taken instead.
     * @param waitTimeout Waiting timeout in seconds (default: 30 seconds, maximum: 60 seconds). Specify 0 to disable waiting.
     * @return Array of <a href="http://www.devicehive.com/restful#Reference/DeviceNotification">DeviceNotification</a>
     */
    @GET
    @RolesAllowed({"CLIENT", "DEVICE", "ADMIN"})
    @Path("/{deviceGuid}/notification/poll")
    @Produces(MediaType.APPLICATION_JSON)
    @JsonPolicyApply(Policy.NOTIFICATION_TO_CLIENT)
    public List<DeviceNotification> poll(
            @PathParam("deviceGuid") String deviceGuid,
            @QueryParam("timestamp") String timestampUTC,
            @QueryParam("waitTimeout") String waitTimeout,
            @Context SecurityContext securityContext) {

        if (deviceGuid == null) {
            throw new NotFoundException();
        }

        Device device = deviceDAO.findByUUID(UUID.fromString(deviceGuid));
        if (device == null) {
            throw new NotFoundException();
        }

        Date timestamp = Params.parseUTCDate(timestampUTC);
        long timeout = Params.parseWaitTimeout(waitTimeout);

        User user = ((HivePrincipal) securityContext.getUserPrincipal()).getUser();

        DeferredResponse result = messageBus.subscribe(MessageType.DEVICE_TO_CLIENT_NOTIFICATION,
                MessageDetails.create().ids(device.getId()).timestamp(timestamp).user(user));
        List<DeviceNotification> response = LocalMessageBus.expandDeferredResponse(result, timeout, DeviceNotification.class);

        return response;
    }

    @GET
    @RolesAllowed({"CLIENT", "DEVICE", "ADMIN"})
    @Path("/notification/poll")
    @Produces(MediaType.APPLICATION_JSON)
    @JsonPolicyApply(Policy.NOTIFICATION_TO_CLIENT)
    public List<DeviceNotification> pollMany(
            @QueryParam("deviceGuids") String deviceGuids,
            @QueryParam("timestamp") String timestampUTC,
            @QueryParam("waitTimeout") String waitTimeout,
            @Context SecurityContext securityContext) {

        List<String> guids = deviceGuids == null ? Collections.<String>emptyList() : Arrays.asList(deviceGuids.split(","));
        List<UUID> uuids = new ArrayList<>(guids.size());
        for (String guid : guids) {
            uuids.add(UUID.fromString(guid));
        }
        
        List<Device> devices = deviceDAO.findByUUID(uuids);
        List<Long> ids = new ArrayList<>(devices.size());
        for (Device device : devices) {
            ids.add(device.getId());
        }

        Date timestamp = Params.parseUTCDate(timestampUTC);
        long timeout = Params.parseWaitTimeout(waitTimeout);

        User user = ((HivePrincipal) securityContext.getUserPrincipal()).getUser();

        DeferredResponse result = messageBus.subscribe(MessageType.DEVICE_TO_CLIENT_NOTIFICATION,
                MessageDetails.create().ids(ids).timestamp(timestamp).user(user));
        List<DeviceNotification> response = LocalMessageBus.expandDeferredResponse(result, timeout, DeviceNotification.class);

        return response;
    }
}
