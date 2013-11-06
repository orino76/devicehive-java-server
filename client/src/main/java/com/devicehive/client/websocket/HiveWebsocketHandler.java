package com.devicehive.client.websocket;


import com.devicehive.client.context.HiveContext;
import com.devicehive.client.json.GsonFactory;
import com.devicehive.client.model.DeviceCommand;
import com.devicehive.client.model.DeviceNotification;
import com.devicehive.client.model.exceptions.HiveException;
import com.devicehive.client.model.exceptions.HiveServerException;
import com.devicehive.client.model.exceptions.InternalHiveClientException;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.log4j.Logger;

import java.util.Map;

import static com.devicehive.client.json.strategies.JsonPolicyDef.Policy.*;

public class HiveWebsocketHandler implements HiveClientEndpoint.MessageHandler {

    private final static String REQUEST_ID_MEMBER = "requestId";
    private final static String ACTION_MEMBER = "action";
    private final static String COMMAND_INSERT = "command/insert";
    private final static String COMMAND_UPDATE = "command/update";
    private final static String NOTIFICATION_INSERT = "notification/insert";
    private final static String CLIENT_MEMBER = "client";
    private final static String NOTIFICATION_MEMBER = "notification";
    private static Logger logger = Logger.getLogger(HiveWebsocketHandler.class);
    private final HiveContext hiveContext;
    private final Map<String, JsonObject> websocketResponsesMap;

    public HiveWebsocketHandler(HiveContext hiveContext, Map responsesMap) {
        this.hiveContext = hiveContext;
        this.websocketResponsesMap = responsesMap;
    }

    @Override
    public void handleMessage(String message) {
        JsonElement elem = new JsonParser().parse(message);
        if (!elem.isJsonObject()) {
            throw new HiveServerException("Server sent unparseable message", 500);
        }
        JsonObject jsonMessage = (JsonObject) elem;
        if (!jsonMessage.has(REQUEST_ID_MEMBER)) {
            try {
                switch (jsonMessage.get(ACTION_MEMBER).getAsString()) {
                    case COMMAND_INSERT:
                        Gson commandInsertGson = GsonFactory.createGson(COMMAND_LISTED);
                        DeviceCommand commandInsert =
                                commandInsertGson.fromJson(jsonMessage.getAsJsonObject(CLIENT_MEMBER),
                                        DeviceCommand.class);
                        hiveContext.getCommandQueue().put(commandInsert);
                        logger.debug("Device command inserted. Id: " + commandInsert.getId());
                        break;
                    case COMMAND_UPDATE:
                        Gson commandUpdateGson = GsonFactory.createGson(COMMAND_UPDATE_TO_CLIENT);
                        DeviceCommand commandUpdated = commandUpdateGson.fromJson(jsonMessage.getAsJsonObject
                                (CLIENT_MEMBER), DeviceCommand.class);
                        hiveContext.getCommandUpdateQueue().put(commandUpdated);
                        logger.debug("Device command updated. Id: " + commandUpdated.getId() + ". Status: " +
                                commandUpdated.getStatus());
                        break;
                    case NOTIFICATION_INSERT:
                        Gson notificationsGson = GsonFactory.createGson(NOTIFICATION_TO_CLIENT);
                        DeviceNotification notification = notificationsGson.fromJson(jsonMessage.getAsJsonObject
                                (NOTIFICATION_MEMBER), DeviceNotification.class);
                        hiveContext.getNotificationQueue().put(notification);
                        logger.debug("Device notification inserted. Id: " + notification.getId());
                        break;
                    default: //unknown request
                        throw new HiveException("Request id is undefined");
                }
            } catch (InterruptedException e) {
                logger.debug(e);
                throw new InternalHiveClientException(e.getMessage(), e);
            }
        } else{
            websocketResponsesMap.put(jsonMessage.get(REQUEST_ID_MEMBER).getAsString(), jsonMessage);
        }
    }
}