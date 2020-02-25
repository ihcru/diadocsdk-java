package Diadoc.Api.counteragent;

import Diadoc.Api.exceptions.DiadocException;
import Diadoc.Api.exceptions.DiadocSdkException;
import Diadoc.Api.helpers.Tools;
import Diadoc.Api.httpClient.DiadocHttpClient;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ByteArrayEntity;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URISyntaxException;

import static Diadoc.Api.Proto.AcquireCounteragentProtos.*;
import static Diadoc.Api.Proto.AsyncMethodResultProtos.*;
import static Diadoc.Api.Proto.CounteragentProtos.*;

public class CounteragentClient {
    private DiadocHttpClient diadocHttpClient;

    public CounteragentClient(DiadocHttpClient diadocHttpClient) {
        this.diadocHttpClient = diadocHttpClient;
    }

    public AsyncMethodResult acquireCounteragent(String myOrgId, AcquireCounteragentRequest acquireCounteragentRequest) throws DiadocSdkException {
        return acquireCounteragent(myOrgId, null, acquireCounteragentRequest);
    }

    public AsyncMethodResult acquireCounteragent(
            String myOrgId,
            @Nullable String myDepartmentId,
            AcquireCounteragentRequest acquireCounteragentRequest) throws DiadocSdkException {
        if (Tools.isNullOrEmpty(myOrgId)) {
            throw new IllegalArgumentException("myOrgId");
        }

        if (acquireCounteragentRequest == null) {
            throw new IllegalArgumentException("acquireCounteragentRequest");
        }

        try {
            URIBuilder url = new URIBuilder(diadocHttpClient.getBaseUrl())
                    .setPath("/V2/AcquireCounteragent")
                    .addParameter("myOrgId", myOrgId);

            if (myDepartmentId != null) {
                url.addParameter("myDepartmentId", myDepartmentId);
            }

            RequestBuilder request = RequestBuilder
                    .post(url.build())
                    .setEntity(new ByteArrayEntity(acquireCounteragentRequest.toByteArray()));

            return AsyncMethodResult.parseFrom(diadocHttpClient.performRequest(request));
        } catch (URISyntaxException | IOException e) {
            throw new DiadocSdkException(e);
        }
    }

    public AcquireCounteragentResult waitAcquireCounteragentResult(String taskId, Integer timeoutInMillis) throws DiadocSdkException, DiadocException {
        try {
            byte[] data = diadocHttpClient.waitTaskResult("/AcquireCounteragentResult", taskId, timeoutInMillis);
            return AcquireCounteragentResult.parseFrom(data);
        } catch (IOException e) {
            throw new DiadocSdkException(e);
        }
    }

    public Counteragent getCounteragent(String myOrgId, String counteragentOrgId) throws DiadocSdkException {
        if (Tools.isNullOrEmpty(myOrgId)) {
            throw new IllegalArgumentException("myOrgId");
        }
        if (Tools.isNullOrEmpty(counteragentOrgId)) {
            throw new IllegalArgumentException("counteragentOrgId");
        }

        try {
            RequestBuilder request = RequestBuilder
                    .get(new URIBuilder(diadocHttpClient.getBaseUrl())
                            .setPath("/V2/GetCounteragent")
                            .addParameter("myOrgId", myOrgId)
                            .addParameter("counteragentOrgId", counteragentOrgId)
                            .build());
            return Counteragent.parseFrom(diadocHttpClient.performRequest(request));
        } catch (URISyntaxException | IOException e) {
            throw new DiadocSdkException(e);
        }
    }

    public CounteragentCertificateList getCounteragentCertificates(String myOrgId, String counteragentOrgId) throws DiadocSdkException {
        if (Tools.isNullOrEmpty(myOrgId)) {
            throw new IllegalArgumentException("myOrgId");
        }
        if (Tools.isNullOrEmpty(counteragentOrgId)) {
            throw new IllegalArgumentException("counteragentOrgId");
        }

        try {
            RequestBuilder request = RequestBuilder
                    .get(new URIBuilder(diadocHttpClient.getBaseUrl())
                            .setPath("/GetCounteragentCertificates")
                            .addParameter("myOrgId", myOrgId)
                            .addParameter("counteragentOrgId", counteragentOrgId)
                            .build());
            return CounteragentCertificateList.parseFrom(diadocHttpClient.performRequest(request));
        } catch (URISyntaxException | IOException e) {
            throw new DiadocSdkException(e);
        }
    }

    public CounteragentList getCounteragents(String myOrgId, @Nullable String counteragentStatus, @Nullable String afterIndexKey) throws DiadocSdkException {
        if (Tools.isNullOrEmpty(myOrgId)) {
            throw new IllegalArgumentException("myOrgId");
        }
        try {
            URIBuilder url = new URIBuilder(diadocHttpClient.getBaseUrl())
                    .setPath("/V2/GetCounteragents")
                    .addParameter("myOrgId", myOrgId);

            if (!Tools.isNullOrEmpty(counteragentStatus)) {
                url.addParameter("counteragentStatus", counteragentStatus);
            }

            if (afterIndexKey != null) {
                url.addParameter("afterIndexKey", afterIndexKey);
            }

            RequestBuilder request = RequestBuilder.get(url.build());
            return CounteragentList.parseFrom(diadocHttpClient.performRequest(request));
        } catch (URISyntaxException | IOException e) {
            throw new DiadocSdkException(e);
        }
    }

    public void breakWithCounteragent(String myOrgId, String counteragentOrgId, @Nullable String comment) throws DiadocSdkException {
        if (Tools.isNullOrEmpty(myOrgId)) {
            throw new IllegalArgumentException("myOrgId");
        }
        if (Tools.isNullOrEmpty(counteragentOrgId)) {
            throw new IllegalArgumentException("counteragentOrgId");
        }

        try {
            URIBuilder url = new URIBuilder(diadocHttpClient.getBaseUrl())
                    .setPath("/BreakWithCounteragent")
                    .addParameter("myOrgId", myOrgId)
                    .addParameter("counteragentOrgId", counteragentOrgId);

            if (!Tools.isNullOrEmpty(comment)) {
                url.addParameter("comment", comment);
            }

            RequestBuilder request = RequestBuilder.post(url.build());
            diadocHttpClient.performRequest(request);
        } catch (URISyntaxException | IOException e) {
            throw new DiadocSdkException(e);
        }
    }
}
