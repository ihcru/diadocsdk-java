package Diadoc.Api.auth;

import Diadoc.Api.Proto.LoginPasswordProtos;
import Diadoc.Api.exceptions.DiadocSdkException;
import Diadoc.Api.httpClient.DiadocHttpClient;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ByteArrayEntity;

import java.io.IOException;
import java.net.URISyntaxException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;

import static Diadoc.Api.Proto.ExternalServiceAuthInfoProtos.ExternalServiceAuthInfo;
import static java.nio.charset.StandardCharsets.UTF_8;

public class AuthenticateClient {
    private static final String V_3_AUTHENTICATE = "/V3/Authenticate";
    private AuthManager authManager;
    private DiadocHttpClient diadocHttpClient;

    public AuthenticateClient(AuthManager authManager, DiadocHttpClient diadocHttpClient) {
        this.authManager = authManager;
        this.diadocHttpClient = diadocHttpClient;
    }

    public void authenticate(String sid) throws DiadocSdkException {
        try {
            authManager.clearCredentials();
            var request = RequestBuilder
                    .post(new URIBuilder(diadocHttpClient.getBaseUrl())
                            .setPath(V_3_AUTHENTICATE)
                            .addParameter("type", "sid")
                            .build())
                    .addHeader("Content-Type", "text/plain")
                    .setEntity(new ByteArrayEntity(sid.getBytes()));

            var response = diadocHttpClient.performRequest(request);
            authManager.setCredentials(new String(response, UTF_8));
        } catch (URISyntaxException | IOException ex) {
            throw new DiadocSdkException(ex);
        }
    }

    public void authenticate(String login, String password) throws DiadocSdkException {
        try {
            authManager.clearCredentials();

            var request = RequestBuilder
                    .post(new URIBuilder(diadocHttpClient.getBaseUrl())
                            .setPath(V_3_AUTHENTICATE)
                            .addParameter("type", "password")
                            .build())
                    .setEntity(new ByteArrayEntity(
                            LoginPasswordProtos.LoginPassword
                                    .newBuilder()
                                    .setLogin(login)
                                    .setPassword(password)
                                    .build()
                                    .toByteArray()));

            var response = diadocHttpClient.performRequest(request);
            authManager.setCredentials(new String(response, UTF_8));
        } catch (IOException | URISyntaxException e) {
            throw new DiadocSdkException(e);
        }

    }

    public void confirmAuthenticationByCertificate(X509Certificate currentCert, String token) throws DiadocSdkException {
        try {
            var request = RequestBuilder.post(
                    new URIBuilder(diadocHttpClient.getBaseUrl())
                            .setPath("/V3/AuthenticateConfirm")
                            .addParameter("token", token)
                            .build())
                    .setEntity(new ByteArrayEntity(currentCert.getEncoded()));

            var response = diadocHttpClient.performRequest(request);

            authManager.setCredentials(StringUtils.newStringUtf8(response));
        } catch (URISyntaxException | CertificateEncodingException | IOException ex) {
            throw new DiadocSdkException(ex);
        }
    }

    public ExternalServiceAuthInfo getExternalServiceAuthInfo(String key) throws DiadocSdkException {
        if (key == null) {
            throw new IllegalArgumentException("key");
        }
        try {
            var request = RequestBuilder.get(
                    new URIBuilder(diadocHttpClient.getBaseUrl())
                            .setPath("/GetExternalServiceAuthInfo")
                            .addParameter("key", key)
                            .build());
            var response = diadocHttpClient.performRequest(request);
            return ExternalServiceAuthInfo.parseFrom(response);
        } catch (URISyntaxException | IOException ex) {
            throw new DiadocSdkException(ex);
        }
    }
}
