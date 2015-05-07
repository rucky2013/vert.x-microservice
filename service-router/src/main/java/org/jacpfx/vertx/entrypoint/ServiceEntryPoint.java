package org.jacpfx.vertx.entrypoint;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.AsyncResultHandler;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.Handler;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.impl.LoggerFactory;
import org.jacpfx.common.*;
import org.jacpfx.vertx.handler.RESTHandler;
import org.jacpfx.vertx.handler.WSClusterHandler;
import org.jacpfx.vertx.handler.WSLocalHandler;
import org.jacpfx.vertx.util.CustomRouteMatcher;
import org.jacpfx.vertx.util.WebSocketRepository;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by Andy Moncsek on 13.11.14.
 */
public class ServiceEntryPoint extends AbstractVerticle {

    public static final String HOST = getHostName();
    public static final int PORT = 8080;
    public static final int DEFAULT_SERVICE_TIMEOUT = 10000;
    public static final String SERVICE_REGISTRY = "org.jacpfx.vertx.registry.ServiceRegistry";
    private static final String SERVICE_INFO_PATH = "/serviceInfo";
    private static final Logger log = LoggerFactory.getLogger(ServiceEntryPoint.class);
    public static final String WS_REPLY = "ws.reply";
    public static final String WS_REPLY_TO_ALL = "ws.replyToAll";
    private static final String prefixWS = "ws://";
    private static final String prefixHTTP = "http://";


    private final Set<String> registeredRoutes = new HashSet<>();
    private final WebSocketRepository repository = new WebSocketRepository();
    private final ServiceInfoDecoder serviceInfoDecoder = new ServiceInfoDecoder();
    private final CustomRouteMatcher routeMatcher = new CustomRouteMatcher();
    private org.jacpfx.vertx.handler.WebSocketHandler wsHandler;
    private RESTHandler restHandler;

    private String serviceInfoPath;
    private String serviceRegisterPath;
    private String serviceUnRegisterPath;
    private String wsReplyPath;
    private String wsReplyToAllPath;
    private String serviceRegistry;
    private String host;
    private String mainURL;
    private boolean clustered;
    private int port;
    private int defaultServiceTimeout;

    public static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            e.printStackTrace();
            return "127.0.0.1";
        }
    }


    @Override
    public void start(io.vertx.core.Future<Void> startFuture) throws Exception {
        log("START ServiceEntryPoint  THREAD: " + Thread.currentThread() + "  this:" + this);
        initConfiguration(getConfig());

        if (clustered) {
            wsHandler = new WSClusterHandler(this.vertx);
        } else {
            wsHandler = new WSLocalHandler(this.vertx);
        }

        // TODO make it configureable if REST should be privided
        restHandler = new RESTHandler(routeMatcher, defaultServiceTimeout, registeredRoutes);
        //vertx.eventBus().registerDefaultCodec(ServiceInfo.class, serviceInfoDecoder);
        //  vertx.eventBus().registerDefaultCodec(Parameter.class, parameterDecoder);


        vertx.eventBus().consumer(wsReplyPath, (Handler<Message<byte[]>>) message -> wsHandler.replyToWSCaller(message));
        vertx.eventBus().consumer(wsReplyToAllPath, (Handler<Message<byte[]>>) message -> wsHandler.replyToAllWS(message));
        vertx.eventBus().consumer(serviceRegisterPath, this::serviceRegisterHandler);
        vertx.eventBus().consumer(serviceUnRegisterPath, this::serviceUnRegisterHandler);
        DeploymentOptions options = new DeploymentOptions().setWorker(false).setConfig(new JsonObject().put("cluster", true));
        vertx.deployVerticle(serviceRegistry, options);

        initHTTPConnector();

        startFuture.complete();
    }


    /**
     * start the server, attach the route matcher
     */
    private void initHTTPConnector() {
        HttpServer server = vertx.createHttpServer(new HttpServerOptions().setHost(host)
                .setPort(port));
        registerWebSocketHandler(server);
        routeMatcher.matchMethod(HttpMethod.GET, serviceInfoPath, this::registerInfoHandler);
        routeMatcher.noMatch(handler -> handler.response().end("no route found"));
        server.requestHandler(routeMatcher::accept).listen(res -> {

        });


    }

    private void initConfiguration(JsonObject config) {
        serviceInfoPath = config.getString("serviceInfoPath", SERVICE_INFO_PATH);
        serviceRegisterPath = config.getString("serviceRegisterPath", GlobalKeyHolder.SERVICE_REGISTER_HANDLER);
        serviceUnRegisterPath = config.getString("serviceUnRegisterPath", GlobalKeyHolder.SERVICE_UNREGISTER_HANDLER);
        wsReplyPath = config.getString("wsReplyPath", WS_REPLY);
        wsReplyToAllPath = config.getString("wsReplyToAllPath", WS_REPLY_TO_ALL);
        serviceRegistry = config.getString("serviceRegistry", SERVICE_REGISTRY);
        clustered = config.getBoolean("clustered", false);
        host = config.getString("host", HOST);
        port = config.getInteger("port", PORT);
        defaultServiceTimeout = config.getInteger("defaultServiceTimeout", DEFAULT_SERVICE_TIMEOUT);
        mainURL = host.concat(":").concat(Integer.valueOf(port).toString());
    }

    /**
     * Unregister service from route
     *
     * @param message the eventbus message for unregistering the service
     */
    private void serviceUnRegisterHandler(final Message<ServiceInfo> message) {
        final ServiceInfo info = message.body();
        Stream.of(info.getOperations()).forEach(operation -> {
            final String url = operation.getUrl();
            restHandler.removeRESTRoutes(url);
        });
    }


    /**
     * Register a service route
     *
     * @param message the eventbus message for registering the service
     */
    private void serviceRegisterHandler(Message<ServiceInfo> message) {
        final ServiceInfo info = message.body();
        final EventBus eventBus = vertx.eventBus();
        Stream.of(info.getOperations()).forEach(operation -> {
                    final String type = operation.getType();
                    final String url = operation.getUrl();
                    final String[] mimes = operation.getProduces() != null ? operation.getProduces() : new String[]{""};
                    // TODO specify timeout in service info object, so that every Service can specify his own timeout
                    // defaultServiceTimeout =   operation.getInteger("timeout");
                    if (!registeredRoutes.contains(url)) {
                        registeredRoutes.add(url);
                        handleServiceType(eventBus, type, url, mimes);
                    }
                }
        );

    }

    private void handleServiceType(EventBus eventBus, String type, String url, String[] mimes) {
        switch (Type.valueOf(type)) {
            case REST_GET:
                restHandler.handleRESTGetRegistration(eventBus, url, mimes);
                break;
            case REST_POST:
                restHandler.handleRESTPostRegistration(eventBus, url, mimes);
                break;
            case EVENTBUS:
                break;
            case WEBSOCKET:
                break;
            default:


        }
    }


    private void registerInfoHandler(HttpServerRequest request) {
        request.response().putHeader("content-type", "text/json");
        vertx.eventBus().send(GlobalKeyHolder.SERVICE_REGISTRY_GET, "xyz", (AsyncResultHandler<Message<JsonObject>>) h ->
                {
                    final Message<JsonObject> message = h.result();
                    final JsonArray servicesArray = message.body().getJsonArray("services");
                    final List<ServiceInfo> infosUpdate = marschallServiceObjectAndUpdateURLs(prefixWS, prefixHTTP, mainURL, servicesArray);
                    final JsonObject result = createServiceInfoJSON(infosUpdate);
                    request.response().end(result.encodePrettily());
                }

        );
    }

    private JsonObject createServiceInfoJSON(List<ServiceInfo> infosUpdate) {
        final JsonArray all = new JsonArray();
        infosUpdate.forEach(handler -> all.add(ServiceInfo.buildFromServiceInfo(handler)));
        return new JsonObject().put("services", all);
    }

    private List<ServiceInfo> marschallServiceObjectAndUpdateURLs(String prefixWS, String prefixHTTP, String mainURL, JsonArray servicesArray) {
        return servicesArray.
                stream().
                map(obj -> (JsonObject) obj).
                map(jsonInfo -> ServiceInfo.buildFromJson(jsonInfo)).
                map(info -> {
                    info.setServiceURL(mainURL.concat(info.getServiceName()));
                    return info;
                }).
                map(infoObj -> {
                    updateOperationUrl(prefixWS, prefixHTTP, mainURL, infoObj);
                    return infoObj;
                }).collect(Collectors.toList());
    }

    private void updateOperationUrl(String prefixWS, String prefixHTTP, String mainURL, ServiceInfo infoObj) {
        Stream<Operation> sOp = Stream.of(infoObj.getOperations());
        sOp.forEach(operation -> {
            final String url = operation.getUrl();
            final Type type = Type.valueOf(operation.getType());
            switch (type) {
                case EVENTBUS:
                    break;
                case WEBSOCKET:
                    operation.setUrl(prefixWS.concat(mainURL).concat(url));
                    break;
                default:
                    operation.setUrl(prefixHTTP.concat(mainURL).concat(url));

            }
        });
    }


    private void registerWebSocketHandler(HttpServer server) {
        server.websocketHandler((serverSocket) -> {
            System.out.println("connect socket to path: " + serverSocket.path());
            serverSocket.pause();
            serverSocket.exceptionHandler(ex -> {
                //TODO
                ex.printStackTrace();
            });
            serverSocket.drainHandler(drain -> {
                //TODO
                log("drain");
            });
            serverSocket.endHandler(end -> {
                //TODO
                log("end");
            });
            serverSocket.closeHandler(close -> {
                wsHandler.findRouteSocketInRegistryAndRemove(serverSocket);
                log("close");
            });
            wsHandler.findRouteToWSServiceAndRegister(serverSocket);
        });
    }

    private JsonObject getConfig() {
        return context != null ? context.config() : new JsonObject();
    }

    private void log(final String value) {
        log.info(value);
    }


}
