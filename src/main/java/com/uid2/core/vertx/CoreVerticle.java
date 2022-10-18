// Copyright (c) 2021 The Trade Desk, Inc
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//
// 1. Redistributions of source code must retain the above copyright notice,
//    this list of conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright notice,
//    this list of conditions and the following disclaimer in the documentation
//    and/or other materials provided with the distribution.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
// AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
// IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
// ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
// LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
// CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
// SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
// INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
// CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
// ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
// POSSIBILITY OF SUCH DAMAGE.

package com.uid2.core.vertx;

import com.uid2.core.model.ConfigStore;
import com.uid2.core.model.Constants;
import com.uid2.core.model.SecretStore;
import com.uid2.core.service.*;
import com.uid2.shared.Const;
import com.uid2.shared.Utils;
import com.uid2.shared.attest.AttestationTokenService;
import com.uid2.shared.attest.IAttestationTokenService;
import com.uid2.shared.auth.*;
import com.uid2.shared.cloud.ICloudStorage;
import com.uid2.shared.health.HealthComponent;
import com.uid2.shared.health.HealthManager;
import com.uid2.shared.middleware.AttestationMiddleware;
import com.uid2.shared.middleware.AuthMiddleware;
import com.uid2.shared.secure.*;
import com.uid2.shared.secure.nitro.InMemoryAWSCertificateStore;
import com.uid2.shared.vertx.RequestCapturingHandler;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.ext.web.handler.CorsHandler;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.KeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.Optional;

public class CoreVerticle extends AbstractVerticle {

    private HealthComponent healthComponent = HealthManager.instance.registerComponent("http-server");

    private final AuthMiddleware auth;
    private final AttestationService attestationService;
    private final AttestationMiddleware attestationMiddleware;
    private final IAuthorizableProvider authProvider;
    private final IEnclaveIdentifierProvider enclaveIdentifierProvider;
    private final Logger logger = LoggerFactory.getLogger(CoreVerticle.class);

    private final IAttestationTokenService attestationTokenService;
    private final IClientMetadataProvider clientMetadataProvider;
    private final IOperatorMetadataProvider operatorMetadataProvider;
    private final IKeyMetadataProvider keyMetadataProvider;
    private final IKeyAclMetadataProvider keyAclMetadataProvider;
    private final ISaltMetadataProvider saltMetadataProvider;
    private final IPartnerMetadataProvider partnerMetadataProvider;

    public CoreVerticle(ICloudStorage cloudStorage, IAuthorizableProvider authProvider, AttestationService attestationService,
                        IAttestationTokenService attestationTokenService, IEnclaveIdentifierProvider enclaveIdentifierProvider) throws Exception
    {
        this.healthComponent.setHealthStatus(false, "not started");

        this.authProvider = authProvider;

        this.attestationService = attestationService;
        this.attestationTokenService = attestationTokenService;
        this.enclaveIdentifierProvider = enclaveIdentifierProvider;
        this.enclaveIdentifierProvider.addListener(this.attestationService);

        this.attestationMiddleware = new AttestationMiddleware(this.attestationTokenService);

        this.auth = new AuthMiddleware(authProvider);

        this.clientMetadataProvider = new ClientMetadataProvider(cloudStorage);
        this.operatorMetadataProvider = new OperatorMetadataProvider(cloudStorage);
        this.keyMetadataProvider = new KeyMetadataProvider(cloudStorage);
        this.keyAclMetadataProvider = new KeyAclMetadataProvider(cloudStorage);
        this.saltMetadataProvider = new SaltMetadataProvider(cloudStorage);
        this.partnerMetadataProvider = new PartnerMetadataProvider(cloudStorage);
    }

    @Override
    public void start(Promise<Void> startPromise) {
        this.healthComponent.setHealthStatus(false, "still starting");

        final Router router = createRoutesSetup();

        final int portOffset = Utils.getPortOffset();
        final int port = Const.Port.ServicePortForCore + portOffset;
        vertx.createHttpServer()
                .requestHandler(router::handle)
                .listen(port, ar -> {
                    if (ar.succeeded()) {
                        this.healthComponent.setHealthStatus(true);
                        startPromise.complete();
                        System.out.println("HTTP server started on port " + port);
                    } else {
                        this.healthComponent.setHealthStatus(false, ar.cause().getMessage());
                        startPromise.fail(ar.cause());
                    }
                });
    }

    private Router createRoutesSetup() {
        final Router router = Router.router(vertx);

        router.route().handler(BodyHandler.create());
        router.route().handler(new RequestCapturingHandler());
        router.route().handler(CorsHandler.create(".*.")
                .allowedMethod(HttpMethod.GET)
                .allowedMethod(HttpMethod.POST)
                .allowedMethod(HttpMethod.OPTIONS)
                .allowedHeader("Access-Control-Request-Method")
                .allowedHeader("Access-Control-Allow-Credentials")
                .allowedHeader("Access-Control-Allow-Origin")
                .allowedHeader("Access-Control-Allow-Headers")
                .allowedHeader("Content-Type"));

        router.post("/attest").handler(auth.handle(this::handleAttestAsync, Role.OPERATOR));
        router.get("/key/refresh").handler(auth.handle(attestationMiddleware.handle(this::handleKeyRefresh), Role.OPERATOR));
        router.get("/key/acl/refresh").handler(auth.handle(attestationMiddleware.handle(this::handleKeyAclRefresh), Role.OPERATOR));
        router.get("/salt/refresh").handler(auth.handle(attestationMiddleware.handle(this::handleSaltRefresh), Role.OPERATOR));
        router.get("/clients/refresh").handler(auth.handle(attestationMiddleware.handle(this::handleClientRefresh), Role.OPERATOR));
        // router.get("/operators/refresh").handler(auth.handle(attestationMiddleware.handle(this::handleOperatorRefresh), Role.OPERATOR));
        // router.get("/partners/refresh").handler(auth.handle(attestationMiddleware.handle(this::handlePartnerRefresh), Role.OPERATOR));
        router.get("/ops/healthcheck").handler(this::handleHealthCheck);

        return router;
    }

    private void handleHealthCheck(RoutingContext rc) {
        if (HealthManager.instance.isHealthy()) {
            rc.response().end("OK");
        } else {
            HttpServerResponse resp = rc.response();
            String reason = HealthManager.instance.reason();
            resp.setStatusCode(503);
            resp.setChunked(true);
            resp.write(reason);
            resp.end();
        }
    }

    private void handleAttestAsync(RoutingContext rc) {
        String token = AuthMiddleware.getAuthToken(rc);
        IAuthorizable profile = authProvider.get(token);
        if(!(profile instanceof OperatorKey)) {
            logger.error("received a call to attestation endpoint with client key (expect operator key): contact " + profile.getContact());
            Error("received a call to attestation endpoint with client key (expect operator key): contact " + profile.getContact(), 400, rc, null);
            return;
        }

        OperatorKey operator = (OperatorKey) profile;
        String protocol = operator.getProtocol();

        JsonObject json;
        try {
            json = rc.getBodyAsJson();
        } catch (DecodeException e) {
            logger.debug("json decode error");
            Error("request body is not a valid json", 400, rc, null);
            return;
        }

        String request = json.getString("attestation_request");
        String clientPublicKey = json.getString("public_key", "");

        if (request == null || request.isEmpty()) {
            logger.debug("no attestation_request attached");
            Error("no attestation_request attached", 400, rc, null);
            return;
        }

        try {
            attestationService.attest(protocol, request, clientPublicKey, ar -> {
                if (!ar.succeeded()) {
                    logger.info("attestation failure: {}", ar.cause());
                    Error("attestation failure", 500, rc, null);
                    return;
                }

                final AttestationResult result = ar.result();
                if (!result.isSuccess()) {
                    Error(result.getReason(), 401, rc, null);
                    return;
                }

                JsonObject responseObj = new JsonObject();
                String attestationToken = attestationTokenService.createToken(
                        token,
                        Instant.now().plus(1, ChronoUnit.DAYS),
                        SecretStore.Global.get(Constants.AttestationEncryptionKeyName),
                        SecretStore.Global.get(Constants.AttestationEncryptionSaltName));

                if(result.getPublicKey() != null) {
                    try {
                        Cipher cipher = Cipher.getInstance(Const.Name.AsymetricEncryptionCipherClass);
                        KeySpec keySpec = new X509EncodedKeySpec(result.getPublicKey());
                        PublicKey publicKey = KeyFactory.getInstance(Const.Name.AsymetricEncryptionKeyClass).generatePublic(keySpec);
                        cipher.init(Cipher.ENCRYPT_MODE, publicKey);
                        attestationToken = Base64.getEncoder().encodeToString(cipher.doFinal(attestationToken.getBytes(StandardCharsets.UTF_8)));
                    } catch (Exception e) {
                        logger.warn("attestation failure: exception while encrypting response - {}", e);
                        Error("attestation failure", 500, rc, null);
                        return;
                    }
                }

                responseObj.put("attestation_token", attestationToken);
                Success(rc, responseObj);
            });
        } catch (AttestationService.NotFound e) {
            logger.info("attestation failure, invalid protocol: {}", protocol);
            Error("protocol not found", 500, rc, null);
            return;
        }
    }

    private void handleSaltRefresh(RoutingContext rc) {
        try {
            rc.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(saltMetadataProvider.getMetadata());
        } catch (Exception e) {
            logger.warn("exception in handleSaltRefresh: " + e.getMessage(), e);
            Error("error", 500, rc, "error processing salt refresh");
        }
    }

    private void handleKeyRefresh(RoutingContext rc) {
        try {
            rc.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(keyMetadataProvider.getMetadata());
        } catch (Exception e) {
            logger.warn("exception in handleKeyRefresh: " + e.getMessage(), e);
            Error("error", 500, rc, "error processing key refresh");
        }
    }

    private void handleKeyAclRefresh(RoutingContext rc) {
        try {
            rc.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                    .end(keyAclMetadataProvider.getMetadata());
        } catch (Exception e) {
            logger.warn("exception in handleKeyAclRefresh: " + e.getMessage(), e);
            Error("error", 500, rc, "error processing key acl refresh");
        }
    }

    private void handleClientRefresh(RoutingContext rc) {
        try {
            rc.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(clientMetadataProvider.getMetadata());
        } catch (Exception e) {
            logger.warn("exception in handleClientRefresh: " + e.getMessage(), e);
            Error("error", 500, rc, "error processing client refresh");
        }
    }

    private void handleOperatorRefresh(RoutingContext rc) {
        try {
            rc.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                    .end(operatorMetadataProvider.getMetadata());
        } catch (Exception e) {
            logger.warn("exception in handleOperatorRefresh: " + e.getMessage(), e);
            Error("error", 500, rc, "error processing operator refresh");
        }
    }

    private void handlePartnerRefresh(RoutingContext rc) {
        try {
            rc.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                    .end(partnerMetadataProvider.getMetadata());
        } catch (Exception e) {
            logger.warn("exception in handlePartnerRefresh: " + e.getMessage(), e);
            Error("error", 500, rc, "error processing partner refresh");
        }
    }

    public static void Success(RoutingContext rc, Object body) {
        final JsonObject json = new JsonObject(new HashMap<String, Object>() {
            {
                put("status", "success");
                put("body", body);
            }
        });
        rc.response().putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(json.encode());
    }

    public static void Error(String errorStatus, int statusCode, RoutingContext rc, String message) {
        final JsonObject json = new JsonObject(new HashMap<String, Object>() {
            {
                put("status", errorStatus);
            }
        });
        if (message != null) {
            json.put("message", message);
        }
        rc.response().setStatusCode(statusCode).putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .end(json.encode());

    }
}
