package Diadoc.Api;

import Diadoc.Api.Proto.*;
import Diadoc.Api.Proto.Docflow.DocflowApiProtos;
import Diadoc.Api.Proto.Documents.*;
import Diadoc.Api.Proto.Events.DiadocMessage_GetApiProtos;
import Diadoc.Api.Proto.Events.DiadocMessage_PostApiProtos;
import Diadoc.Api.Proto.Invoicing.*;
import Diadoc.Api.Proto.Invoicing.Signers.ExtendedSignerProtos;
import Diadoc.Api.Proto.Recognition.RecognitionProtos;
import com.google.gson.Gson;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import org.apache.commons.io.IOUtils;
import org.apache.http.*;
import org.apache.http.auth.AuthScope;
import org.apache.http.client.HttpClient;
import org.apache.http.client.HttpResponseException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.client.utils.URLEncodedUtils;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.params.HttpProtocolParams;

import javax.mail.internet.ContentDisposition;
import javax.mail.internet.ParseException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.TimeoutException;

public class DiadocApi {

    private static final int SHELF_PART_LENGTH = 512 * 1024;
    private static final int SHELF_MAX_ATTEMPTS = 3;
    private static final List<Integer> SHELF_NON_RETRIABLE_STATUS_CODES = new ArrayList<Integer>();
    static
    {
        SHELF_NON_RETRIABLE_STATUS_CODES.add(HttpStatus.SC_UNAUTHORIZED);
        SHELF_NON_RETRIABLE_STATUS_CODES.add(HttpStatus.SC_FORBIDDEN);
        SHELF_NON_RETRIABLE_STATUS_CODES.add(HttpStatus.SC_PAYMENT_REQUIRED);
    }


    private String url;
    private String apiClientId;
    private DefaultHttpClient httpClient;
    private boolean authenticated;

    public boolean IsAuthenticated() {
        return authenticated;
    }

    public DiadocApi(String apiClientId, String url)
            throws KeyManagementException, NoSuchAlgorithmException {
        if (url == null)
            throw new NullPointerException("url");
        this.url = url;
        this.apiClientId = apiClientId;
        this.httpClient = createHttpClient();
        updateCredentials(null);
    }

    private static DefaultHttpClient createHttpClient()
            throws NoSuchAlgorithmException, KeyManagementException {
        DefaultHttpClient defaultHttpClient = new DefaultHttpClient();
        HttpProtocolParams.setUserAgent(defaultHttpClient.getParams(), getUserAgentString());
        defaultHttpClient = makeTrustfulHttpClient(defaultHttpClient);
        defaultHttpClient.addRequestInterceptor(new DiadocPreemptiveAuthRequestInterceptor(), 0);
        return defaultHttpClient;
    }

    private static String getUserAgentString()
    {
        return getDiadocSdkVersion() + ";" + getJavaRuntimeVersion();
    }

    private static String getDiadocSdkVersion(){
        try
        {
            Package currentPackage = DiadocApi.class.getPackage();
            String version = currentPackage.getSpecificationVersion();
            if (version == null || version.length() == 0)
                version = currentPackage.getImplementationVersion();
            if (version == null || version.length() == 0)
                version = "Unknown";
            return "Diadoc SDK for Java v" + version;
        }
        catch (Exception e)
        {
            return "Diadoc SDK for Java";
        }
    }

    private static String getJavaRuntimeVersion() {
        return "JRE=" + System.getProperty("java.version")
            +  ";Vendor=" + System.getProperty("java.vendor")
            +  ";OS=" + System.getProperty("os.name") + " v" + System.getProperty("os.version")
            +  ";Arch=" + System.getProperty("os.arch");
    }

    private static DefaultHttpClient makeTrustfulHttpClient(HttpClient base)
            throws NoSuchAlgorithmException, KeyManagementException {
        SSLSocketFactory ssf = getTrustfulSslSocketFactory();
        ClientConnectionManager ccm = base.getConnectionManager();
        ccm.getSchemeRegistry().register(new Scheme("https", 443, ssf));
        return new DefaultHttpClient(ccm, base.getParams());
    }

    private static SSLSocketFactory getTrustfulSslSocketFactory() throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext ctx = SSLContext.getInstance("TLS");
        X509TrustManager tm = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] xcs, String string)
                    throws CertificateException {
            }

            public void checkServerTrusted(X509Certificate[] xcs, String string)
                    throws CertificateException {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return null;
            }
        };
        ctx.init(null, new TrustManager[]{tm}, null);
        return new SSLSocketFactory(ctx, SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
    }

    public void Authenticate(String login, String password) throws IOException {
        updateCredentials(null);
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("login", login));
        parameters.add(new BasicNameValuePair("password", password));
        byte[] httpResponse = PerformPostHttpRequest("/Authenticate", parameters, null);
        String result;
        try {
            result = new String(httpResponse, "UTF8");
        } catch (UnsupportedEncodingException e) {
            result = e.toString();
        }
        updateCredentials(result);
    }

    private void updateCredentials(String authToken) {
        authenticated = (authToken != null);
        DiadocCredentials diadocCredentials = new DiadocCredentials(apiClientId, authToken);
        httpClient.getCredentialsProvider().setCredentials(AuthScope.ANY, diadocCredentials);
    }

    public byte[] ShelfDownload(String nameOnShelf) throws IOException {
        if (!nameOnShelf.contains("__userId__"))
            nameOnShelf = "__userId__/" + nameOnShelf;
        List<NameValuePair> params = new ArrayList<NameValuePair>();
        params.add(new BasicNameValuePair("nameOnShelf", nameOnShelf));
        return PerformGetHttpRequest("/ShelfDownload", params);
    }

    public DiadocMessage_GetApiProtos.Message PostMessage(DiadocMessage_PostApiProtos.MessageToPost msg) throws IOException {
        if (msg == null)
            throw new NullPointerException("msg");
        try {
            byte[] responseBytes = PerformPostHttpRequest("/V3/PostMessage", null, msg.toByteArray());
            return DiadocMessage_GetApiProtos.Message.parseFrom(responseBytes);
        } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
            return null;
        }
    }

    public void Delete(String boxId, String messageId, String documentId) throws Exception {
        if (boxId == null) throw new NullPointerException("boxId");
        if (messageId == null) throw new NullPointerException("messageId");
        List<NameValuePair> params = new ArrayList<NameValuePair>();
        params.add(new BasicNameValuePair("boxId", boxId));
        params.add(new BasicNameValuePair("messageId", messageId));
        if (documentId != null)
            params.add(new BasicNameValuePair("documentId", documentId));
        PerformPostHttpRequest("/Delete", params, new byte[]{});
    }

    private URI BuildRequestURI(URI baseUri, String path, List<NameValuePair> parameters) throws URISyntaxException {
        String query = parameters != null ? URLEncodedUtils.format(parameters, "UTF-8") : null;
        return URIUtils.createURI(baseUri.getScheme(), baseUri.getHost(), baseUri.getPort(), path, query, null);
    }

    private HttpResponse ReceiveGetHttpResponse(String path, List<NameValuePair> parameters) throws IOException {
        try {
            URI baseUri = new URI(url);
            HttpGet httpGet = new HttpGet(BuildRequestURI(baseUri, path, parameters));
            return httpClient.execute(httpGet);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }

        return null;
    }

    private HttpResponse ReceivePostHttpResponse(String path, List<NameValuePair> parameters, byte[] requestBody) throws IOException {
        try {
            URI baseUri = new URI(url);
            HttpPost httpPost = new HttpPost(BuildRequestURI(baseUri, path, parameters));
            if (requestBody != null)
                httpPost.setEntity(new ByteArrayEntity(requestBody));
            return httpClient.execute(httpPost);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }

        return null;
    }

    private byte[] PerformGetHttpRequest(String path, List<NameValuePair> parameters) throws IOException {
        HttpResponse response = ReceiveGetHttpResponse(path, parameters);
        return GetResponseBytes(response);
    }

    private byte[] GetResponseBytes(HttpResponse response) throws IOException {
        if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK)
            throw new HttpResponseException(response.getStatusLine().getStatusCode(), new String(IOUtils.toByteArray(response.getEntity().getContent()),"UTF8"));
        return IOUtils.toByteArray(response.getEntity().getContent());
    }

    private byte[] PerformPostHttpRequest(String path, List<NameValuePair> parameters, byte[] requestBody) throws IOException {
        HttpResponse response = ReceivePostHttpResponse(path, parameters, requestBody);
        return GetResponseBytes(response);
    }

    public OrganizationProtos.OrganizationList GetMyOrganizations() throws IOException {
        return GetMyOrganizations(true);
    }

    public OrganizationProtos.OrganizationList GetMyOrganizations(boolean autoRegister) throws IOException {
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        if (!autoRegister)
            parameters.add(new BasicNameValuePair("autoRegister", Boolean.toString(false)));
        return OrganizationProtos.OrganizationList.parseFrom(PerformGetHttpRequest("/GetMyOrganizations", parameters));
    }

    public OrganizationUserPermissionsProtos.OrganizationUserPermissions GetMyPermissions(String orgId) throws IOException {
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("orgId", orgId));
        return OrganizationUserPermissionsProtos.OrganizationUserPermissions.parseFrom(PerformGetHttpRequest("/GetMyPermissions", parameters));
    }

    public OrganizationUserProtos.OrganizationUsersList GetOrganizationUsers(String orgId) throws IOException {
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("orgId", orgId));
        return OrganizationUserProtos.OrganizationUsersList.parseFrom(PerformGetHttpRequest("/GetOrganizationUsers", parameters));
    }

    public DiadocMessage_GetApiProtos.BoxEventList GetNewEvents(String currentBoxId, String eventIdCurrent) throws IOException {
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("includeDrafts", null));
        parameters.add(new BasicNameValuePair("boxId", currentBoxId));
        if (eventIdCurrent != null)
            parameters.add(new BasicNameValuePair("afterEventId", eventIdCurrent));
        return DiadocMessage_GetApiProtos.BoxEventList.parseFrom(PerformGetHttpRequest("/V5/GetNewEvents", parameters));
    }

    public OrganizationProtos.OrganizationList GetOrganizationsByInnKpp(String inn, String kpp) throws IOException {
        return GetOrganizationsByInnKpp(inn, kpp, false);
    }

    public OrganizationProtos.OrganizationList GetOrganizationsByInnKpp(String inn, String kpp, boolean includeRelations) throws IOException {
        if (inn == null)
            throw new NullPointerException("inn");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("inn", inn));
        if (!Tools.IsNullOrEmpty(kpp))
            parameters.add(new BasicNameValuePair("kpp", kpp));
        if(includeRelations)
            parameters.add(new BasicNameValuePair("includeRelations", "true"));
        return OrganizationProtos.OrganizationList.parseFrom(PerformGetHttpRequest("/GetOrganizationsByInnKpp", parameters));
    }

    public List<OrganizationProtos.Organization> GetOrganizationsByInnList(
            GetOrganizationsByInnListProtos.GetOrganizationsByInnListRequest request) throws IOException {
        if (request == null)
            throw new NullPointerException("request");
        byte[] responseBytes = PerformPostHttpRequest("/GetOrganizationsByInnList", null, request.toByteArray());
        GetOrganizationsByInnListProtos.GetOrganizationsByInnListResponse response =
            GetOrganizationsByInnListProtos.GetOrganizationsByInnListResponse.parseFrom(responseBytes);
        List<OrganizationProtos.Organization> result = new ArrayList<OrganizationProtos.Organization>();
        for (GetOrganizationsByInnListProtos.OrganizationWithCounteragentStatus org: response.getOrganizationsList())
            result.add(org.getOrganization());
        return result;
    }

    public List<OrganizationProtos.Organization> GetOrganizationsByInnList(Iterable<String> innList) throws IOException {
        GetOrganizationsByInnListProtos.GetOrganizationsByInnListRequest.Builder request =
                GetOrganizationsByInnListProtos.GetOrganizationsByInnListRequest.newBuilder();
        request.addAllInnList(innList);
        return GetOrganizationsByInnList(request.build());
    }

    public List<GetOrganizationsByInnListProtos.OrganizationWithCounteragentStatus> GetOrganizationsByInnList(String myOrgId,
            GetOrganizationsByInnListProtos.GetOrganizationsByInnListRequest request) throws IOException {
        if (myOrgId == null)
            throw new NullPointerException("myOrgId");
        if (request == null)
            throw new NullPointerException("request");

        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("myOrgId", myOrgId));

        byte[] responseBytes = PerformPostHttpRequest("/GetOrganizationsByInnList", parameters, request.toByteArray());
        GetOrganizationsByInnListProtos.GetOrganizationsByInnListResponse response =
            GetOrganizationsByInnListProtos.GetOrganizationsByInnListResponse.parseFrom(responseBytes);
        return response.getOrganizationsList();
    }

    public List<GetOrganizationsByInnListProtos.OrganizationWithCounteragentStatus> GetOrganizationsByInnList(String myOrgId, Iterable<String> innList) throws IOException {
        GetOrganizationsByInnListProtos.GetOrganizationsByInnListRequest.Builder request =
            GetOrganizationsByInnListProtos.GetOrganizationsByInnListRequest.newBuilder();
        request.addAllInnList(innList);
        return GetOrganizationsByInnList(myOrgId, request.build());
    }

    public OrganizationProtos.Organization GetOrganizationById(String orgId) throws IOException {
        return GetOrganization(orgId, "orgId");
    }

    public OrganizationProtos.Organization GetOrganizationByInn(String inn) throws IOException {
        return GetOrganization(inn, "inn");
    }

    public OrganizationProtos.Organization GetOrganizationByFnsParticipantId(String fnsParticipantId) throws IOException {
        return GetOrganization(fnsParticipantId, "fnsParticipantId");
    }

    public OrganizationProtos.Box GetBox(String boxId) throws IOException {
        if (boxId == null)
            throw new NullPointerException("boxId");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        return OrganizationProtos.Box.parseFrom(PerformGetHttpRequest("/GetBox", parameters));
    }

    public OrganizationProtos.Department GetDepartment(String orgId, String departmentId) throws IOException {
        if (orgId == null)
            throw new NullPointerException("orgId");
        if (departmentId == null)
            throw new NullPointerException("departmentId");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("orgId", orgId));
        parameters.add(new BasicNameValuePair("departmentId", departmentId));
        return OrganizationProtos.Department.parseFrom(PerformGetHttpRequest("/GetDepartment", parameters));
    }

    public ExternalServiceAuthInfoProtos.ExternalServiceAuthInfo GetExternalServiceAuthInfo(String key) throws IOException {
        if (key == null)
            throw new NullPointerException("key");
        // List<NameValuePair>
        List parameters = new ArrayList();
        parameters.add(new BasicNameValuePair("key", key));
        return ExternalServiceAuthInfoProtos.ExternalServiceAuthInfo.parseFrom(PerformGetHttpRequest("/GetExternalServiceAuthInfo", parameters));
    }

    public byte[] GetEntityContent(String currentBoxId, String messageId, String entityId) throws IOException {
        if (currentBoxId == null)
            throw new NullPointerException("boxId");
        if (messageId == null)
            throw new NullPointerException("messageId");
        if (entityId == null)
            throw new NullPointerException("entityId");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", currentBoxId));
        parameters.add(new BasicNameValuePair("messageId", messageId));
        parameters.add(new BasicNameValuePair("entityId", entityId));
        return PerformGetHttpRequest("/V4/GetEntityContent", parameters);
    }

    public DiadocMessage_GetApiProtos.Message GetMessage(String currentBoxId, String messageId) throws IOException {
        if (currentBoxId == null)
            throw new NullPointerException("boxId");
        if (messageId == null)
            throw new NullPointerException("messageId");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", currentBoxId));
        parameters.add(new BasicNameValuePair("messageId", messageId));
        return DiadocMessage_GetApiProtos.Message.parseFrom(PerformGetHttpRequest("/V3/GetMessage", parameters));
    }

    public DiadocMessage_GetApiProtos.Message GetMessage(String currentBoxId, String messageId, String entityId) throws IOException {
        if (currentBoxId == null)
            throw new NullPointerException("boxId");
        if (messageId == null)
            throw new NullPointerException("messageId");
        if (entityId == null)
            throw new NullPointerException("entityId");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", currentBoxId));
        parameters.add(new BasicNameValuePair("messageId", messageId));
        parameters.add(new BasicNameValuePair("entityId", entityId));
        return DiadocMessage_GetApiProtos.Message.parseFrom(PerformGetHttpRequest("/V3/GetMessage", parameters));
    }

    public GeneratedFile GenerateInvoiceDocumentReceiptXml(String boxId, String messageId, String attachmentId, SignerProtos.Signer signer)
            throws IllegalStateException, IOException, ParseException {
        if (boxId == null)
            throw new NullPointerException("boxId");
        if (messageId == null)
            throw new NullPointerException("messageId");
        if (attachmentId == null)
            throw new NullPointerException("attachmentId");
        if (signer == null)
            throw new NullPointerException("signer");

        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        parameters.add(new BasicNameValuePair("messageId", messageId));
        parameters.add(new BasicNameValuePair("attachmentId", attachmentId));
        HttpResponse webResponse = ReceivePostHttpResponse("/GenerateInvoiceDocumentReceiptXml", parameters, signer.toByteArray());

        return new GeneratedFile(GetHttpResponseFileName(webResponse), GetResponseBytes(webResponse));
    }
    public GeneratedFile GenerateDocumentReceiptXml(String boxId, String messageId, String attachmentId, SignerProtos.Signer signer)
            throws IllegalStateException, IOException, ParseException {
        if (boxId == null)
            throw new NullPointerException("boxId");
        if (messageId == null)
            throw new NullPointerException("messageId");
        if (attachmentId == null)
            throw new NullPointerException("attachmentId");
        if (signer == null)
            throw new NullPointerException("signer");

        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        parameters.add(new BasicNameValuePair("messageId", messageId));
        parameters.add(new BasicNameValuePair("attachmentId", attachmentId));
        HttpResponse webResponse = ReceivePostHttpResponse("/GenerateDocumentReceiptXml", parameters, signer.toByteArray());

        return new GeneratedFile(GetHttpResponseFileName(webResponse), GetResponseBytes(webResponse));
    }


    private static String GetHttpResponseFileName(HttpResponse webResponse) throws ParseException {
        Header[] dispositions = webResponse.getHeaders("Content-Disposition");
        if (dispositions == null || dispositions.length == 0)
            return null;
        return new ContentDisposition(dispositions[0].getValue()).getParameter("filename");
    }

    public GeneratedFile GenerateInvoiceCorrectionRequestXml(String boxId, String messageId, String attachmentId,
            InvoiceCorrectionRequestInfoProtos.InvoiceCorrectionRequestInfo invoiceCorrectionInfo) throws IllegalStateException, IOException, ParseException {
        if (boxId == null)
            throw new NullPointerException("boxId");
        if (messageId == null)
            throw new NullPointerException("messageId");
        if (attachmentId == null)
            throw new NullPointerException("attachmentId");

        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        parameters.add(new BasicNameValuePair("messageId", messageId));
        parameters.add(new BasicNameValuePair("attachmentId", attachmentId));
        HttpResponse webResponse = ReceivePostHttpResponse("/GenerateInvoiceCorrectionRequestXml", parameters, invoiceCorrectionInfo.toByteArray());

        return new GeneratedFile(GetHttpResponseFileName(webResponse), GetResponseBytes(webResponse));
    }

    public GeneratedFile GenerateRevocationRequestXml(String boxId, String messageId, String attachmentId,
            RevocationRequestInfoProtos.RevocationRequestInfo revocationRequestInfo) throws IllegalStateException, IOException, ParseException {
        if (boxId == null)
            throw new NullPointerException("boxId");
        if (messageId == null)
            throw new NullPointerException("messageId");
        if (attachmentId == null)
            throw new NullPointerException("attachmentId");

        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        parameters.add(new BasicNameValuePair("messageId", messageId));
        parameters.add(new BasicNameValuePair("attachmentId", attachmentId));
        HttpResponse webResponse = ReceivePostHttpResponse("/GenerateRevocationRequestXml", parameters, revocationRequestInfo.toByteArray());

        return new GeneratedFile(GetHttpResponseFileName(webResponse), GetResponseBytes(webResponse));
    }

    public GeneratedFile GenerateSignatureRejectionXml(String boxId, String messageId, String attachmentId,
            SignatureRejectionInfoProtos.SignatureRejectionInfo signatureRejectionInfo) throws IllegalStateException, IOException, ParseException {
        if (boxId == null)
            throw new NullPointerException("boxId");
        if (messageId == null)
            throw new NullPointerException("messageId");
        if (attachmentId == null)
            throw new NullPointerException("attachmentId");

        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        parameters.add(new BasicNameValuePair("messageId", messageId));
        parameters.add(new BasicNameValuePair("attachmentId", attachmentId));
        HttpResponse webResponse = ReceivePostHttpResponse("/GenerateSignatureRejectionXml", parameters, signatureRejectionInfo.toByteArray());

        return new GeneratedFile(GetHttpResponseFileName(webResponse), GetResponseBytes(webResponse));
    }

    public GeneratedFile GenerateInvoiceXml(InvoiceInfoProtos.InvoiceInfo invoiceInfo) throws IOException, ParseException {
        return GenerateInvoiceXml(invoiceInfo, "Invoice", false);
    }

    public GeneratedFile GenerateInvoiceXml(InvoiceInfoProtos.InvoiceInfo invoiceInfo, boolean disableValidation) throws IOException, ParseException {
        return GenerateInvoiceXml(invoiceInfo, "Invoice", disableValidation);
    }

    public GeneratedFile GenerateInvoiceRevisionXml(InvoiceInfoProtos.InvoiceInfo invoiceRevisionInfo) throws IOException, ParseException {
        return GenerateInvoiceXml(invoiceRevisionInfo, "InvoiceRevision", false);
    }

    public GeneratedFile GenerateInvoiceRevisionXml(InvoiceInfoProtos.InvoiceInfo invoiceRevisionInfo, boolean disableValidation) throws IOException, ParseException {
        return GenerateInvoiceXml(invoiceRevisionInfo, "InvoiceRevision", disableValidation);
    }

    public GeneratedFile GenerateInvoiceCorrectionXml(InvoiceInfoProtos.InvoiceCorrectionInfo invoiceCorrectionInfo) throws IOException, ParseException {
        return GenerateInvoiceXml(invoiceCorrectionInfo, "InvoiceCorrection", false);
    }

    public GeneratedFile GenerateInvoiceCorrectionXml(InvoiceInfoProtos.InvoiceCorrectionInfo invoiceCorrectionInfo, boolean disableValidation) throws IOException, ParseException {
        return GenerateInvoiceXml(invoiceCorrectionInfo, "InvoiceCorrection", disableValidation);
    }

    public GeneratedFile GenerateInvoiceCorrectionRevisionXml(InvoiceInfoProtos.InvoiceCorrectionInfo invoiceCorrectionRevisionInfo) throws IOException, ParseException {
        return GenerateInvoiceXml(invoiceCorrectionRevisionInfo, "InvoiceCorrectionRevision", false);
    }

    public GeneratedFile GenerateInvoiceCorrectionRevisionXml(InvoiceInfoProtos.InvoiceCorrectionInfo invoiceCorrectionRevisionInfo, boolean disableValidation) throws IOException, ParseException {
        return GenerateInvoiceXml(invoiceCorrectionRevisionInfo, "InvoiceCorrectionRevision", disableValidation);
    }

    private GeneratedFile GenerateInvoiceXml(MessageLite invoiceInfo, String invoiceType, boolean disableValidation) throws IOException, ParseException {
        if (invoiceInfo == null) throw new NullPointerException("info");
        HttpResponse httpResponse = ReceivePostHttpResponse("/GenerateInvoiceXml?invoiceType=" + invoiceType +
                (disableValidation ? "&disableValidation" : ""), null, invoiceInfo.toByteArray());
        return new GeneratedFile(GetHttpResponseFileName(httpResponse), GetResponseBytes(httpResponse));
    }

    public GeneratedFile GenerateTorg12XmlForSeller(Torg12InfoProtos.Torg12SellerTitleInfo sellerTitleInfo) throws IOException, ParseException {
        return GenerateTorg12XmlForSeller(sellerTitleInfo, false);
    }

    public GeneratedFile GenerateTorg12XmlForSeller(Torg12InfoProtos.Torg12SellerTitleInfo sellerTitleInfo, boolean disableValidation) throws IOException, ParseException {
        if (sellerTitleInfo == null) throw new NullPointerException("sellerTitleInfo");
        HttpResponse httpResponse = ReceivePostHttpResponse("/GenerateTorg12XmlForSeller" + (disableValidation?"?disableValidation":""), null, sellerTitleInfo.toByteArray());
        return new GeneratedFile(GetHttpResponseFileName(httpResponse), GetResponseBytes(httpResponse));
    }

    public GeneratedFile GenerateTorg12XmlForBuyer(Torg12InfoProtos.Torg12BuyerTitleInfo buyerTitleInfo, String boxId, String sellerTitleMessageId, String sellerTitleAttachmentId) throws IOException, ParseException {
        if (buyerTitleInfo == null) throw new NullPointerException("buyerTitleInfo");
        if (boxId == null) throw new NullPointerException("boxId");
        if (sellerTitleMessageId == null) throw new NullPointerException("sellerTitleMessageId");
        if (sellerTitleAttachmentId == null) throw new NullPointerException("sellerTitleAttachmentId");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        parameters.add(new BasicNameValuePair("sellerTitleMessageId", sellerTitleMessageId));
        parameters.add(new BasicNameValuePair("sellerTitleAttachmentId", sellerTitleAttachmentId));
        HttpResponse httpResponse = ReceivePostHttpResponse("/GenerateTorg12XmlForBuyer", parameters, buyerTitleInfo.toByteArray());
        return new GeneratedFile(GetHttpResponseFileName(httpResponse), GetResponseBytes(httpResponse));
    }

    public GeneratedFile GenerateAcceptanceCertificateXmlForSeller(AcceptanceCertificateInfoProtos.AcceptanceCertificateSellerTitleInfo sellerTitleInfo) throws IOException, ParseException {
        return GenerateAcceptanceCertificateXmlForSeller(sellerTitleInfo, false);
    }

    public GeneratedFile GenerateAcceptanceCertificateXmlForSeller(AcceptanceCertificateInfoProtos.AcceptanceCertificateSellerTitleInfo sellerTitleInfo, boolean disableValidation) throws IOException, ParseException {
        if (sellerTitleInfo == null) throw new NullPointerException("sellerTitleInfo");
        HttpResponse httpResponse = ReceivePostHttpResponse("/GenerateAcceptanceCertificateXmlForSeller" + (disableValidation?"?disableValidation":""), null, sellerTitleInfo.toByteArray());
        return new GeneratedFile(GetHttpResponseFileName(httpResponse), GetResponseBytes(httpResponse));
    }

    public GeneratedFile GenerateAcceptanceCertificateXmlForBuyer(AcceptanceCertificateInfoProtos.AcceptanceCertificateBuyerTitleInfo buyerTitleInfo, String boxId, String sellerTitleMessageId,
                                                                  String sellerTitleAttachmentId) throws IOException, ParseException {
        if (buyerTitleInfo == null) throw new NullPointerException("buyerTitleInfo");
        if (boxId == null) throw new NullPointerException("boxId");
        if (sellerTitleMessageId == null) throw new NullPointerException("sellerTitleMessageId");
        if (sellerTitleAttachmentId == null) throw new NullPointerException("sellerTitleAttachmentId");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        parameters.add(new BasicNameValuePair("sellerTitleMessageId", sellerTitleMessageId));
        parameters.add(new BasicNameValuePair("sellerTitleAttachmentId", sellerTitleAttachmentId));
        HttpResponse httpResponse = ReceivePostHttpResponse("/GenerateAcceptanceCertificateXmlForBuyer", parameters, buyerTitleInfo.toByteArray());
        return new GeneratedFile(GetHttpResponseFileName(httpResponse), GetResponseBytes(httpResponse));
    }

    public GeneratedFile GenerateUniversalTransferDocumentXmlForSeller(UniversalTransferDocumentInfoProtos.UniversalTransferDocumentSellerTitleInfo utdInfo) throws IOException, ParseException {
        return GenerateUniversalTransferDocumentXmlForSeller(utdInfo, false);
    }

    public GeneratedFile GenerateUniversalTransferDocumentXmlForSeller(UniversalTransferDocumentInfoProtos.UniversalTransferDocumentSellerTitleInfo utdInfo, boolean disableValidation) throws IOException, ParseException {
        if (utdInfo == null) throw new NullPointerException("info");
        HttpResponse httpResponse = ReceivePostHttpResponse(
            "/GenerateUniversalTransferDocumentXmlForSeller" + (disableValidation ? "?disableValidation" : ""),
            null,
            utdInfo.toByteArray());
        return new GeneratedFile(GetHttpResponseFileName(httpResponse), GetResponseBytes(httpResponse));
    }

    public GeneratedFile GenerateUniversalCorrectionTransferDocumentXmlForSeller(UniversalTransferDocumentInfoProtos.UniversalCorrectionDocumentSellerTitleInfo ucdInfo) throws IOException, ParseException {
        return GenerateUniversalCorrectionTransferDocumentXmlForSeller(ucdInfo, false);
    }

    public GeneratedFile GenerateUniversalCorrectionTransferDocumentXmlForSeller(UniversalTransferDocumentInfoProtos.UniversalCorrectionDocumentSellerTitleInfo ucdInfo, boolean disableValidation) throws IOException, ParseException {
        if (ucdInfo == null) throw new NullPointerException("info");
        HttpResponse httpResponse = ReceivePostHttpResponse(
            "/GenerateUniversalTransferDocumentXmlForSeller"
                + "?correction"
                + (disableValidation ? "&disableValidation" : ""),
            null,
            ucdInfo.toByteArray());
        return new GeneratedFile(GetHttpResponseFileName(httpResponse), GetResponseBytes(httpResponse));
    }

    public GeneratedFile GenerateUniversalTransferDocumentXmlForBuyer(
        UniversalTransferDocumentInfoProtos.UniversalTransferDocumentBuyerTitleInfo buyerTitleInfo,
        String boxId,
        String sellerTitleMessageId,
        String sellerTitleAttachmentId
    ) throws IOException, ParseException {
        if (buyerTitleInfo == null) throw new NullPointerException("buyerTitleInfo");
        if (boxId == null) throw new NullPointerException("boxId");
        if (sellerTitleMessageId == null) throw new NullPointerException("sellerTitleMessageId");
        if (sellerTitleAttachmentId == null) throw new NullPointerException("sellerTitleAttachmentId");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        parameters.add(new BasicNameValuePair("sellerTitleMessageId", sellerTitleMessageId));
        parameters.add(new BasicNameValuePair("sellerTitleAttachmentId", sellerTitleAttachmentId));
        HttpResponse httpResponse = ReceivePostHttpResponse("/GenerateUniversalTransferDocumentXmlForBuyer", parameters, buyerTitleInfo.toByteArray());
        return new GeneratedFile(GetHttpResponseFileName(httpResponse), GetResponseBytes(httpResponse));
    }

    public ExtendedSignerProtos.ExtendedSignerDetails GetExtendedSignerDetails(String boxId, String thumbprint, boolean forBuyer, boolean forCorrection) throws IOException
    {
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        parameters.add(new BasicNameValuePair("thumbprint", thumbprint));
        if (forBuyer)
            parameters.add(new BasicNameValuePair("buyer", "true"));
        if (forCorrection)
            parameters.add(new BasicNameValuePair("correction", "true"));
        return ExtendedSignerProtos.ExtendedSignerDetails.parseFrom(
            PerformGetHttpRequest("/ExtendedSignerDetails", parameters)
        );
    }

    public ExtendedSignerProtos.ExtendedSignerDetails PostExtendedSignerDetails(String boxId, String thumbprint, boolean forBuyer, boolean forCorrection, ExtendedSignerProtos.ExtendedSignerDetailsToPost signerDetails) throws IOException
    {
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        parameters.add(new BasicNameValuePair("thumbprint", thumbprint));
        if (forBuyer)
            parameters.add(new BasicNameValuePair("buyer", "true"));
        if (forCorrection)
            parameters.add(new BasicNameValuePair("correction", "true"));
        return ExtendedSignerProtos.ExtendedSignerDetails.parseFrom(
            PerformPostHttpRequest("/ExtendedSignerDetails", parameters, signerDetails.toByteArray())
        );
    }

    public InvoiceInfoProtos.InvoiceInfo ParseInvoiceXml(byte[] invoiceXmlContent) throws IOException
    {
        return InvoiceInfoProtos.InvoiceInfo.parseFrom(PerformPostHttpRequest("/ParseInvoiceXml", null, invoiceXmlContent));
    }

    public Torg12InfoProtos.Torg12SellerTitleInfo ParseTorg12SellerTitleXml(byte[] sellerTitleXmlContent) throws IOException
    {
        return Torg12InfoProtos.Torg12SellerTitleInfo.parseFrom(PerformPostHttpRequest("/ParseTorg12SellerTitleXml", null, sellerTitleXmlContent));
    }

    public AcceptanceCertificateInfoProtos.AcceptanceCertificateSellerTitleInfo ParseAcceptanceCertificateSellerTitleXml(byte[] sellerTitleXmlContent) throws IOException
    {
        return AcceptanceCertificateInfoProtos.AcceptanceCertificateSellerTitleInfo.parseFrom(PerformPostHttpRequest("/ParseAcceptanceCertificateSellerTitleXml", null, sellerTitleXmlContent));
    }

    public UniversalTransferDocumentInfoProtos.UniversalTransferDocumentSellerTitleInfo ParseUniversalTransferDocumentSellerTitleXml(byte[] utdXmlContent) throws IOException
    {
        byte[] response = PerformPostHttpRequest("/ParseUniversalTransferDocumentSellerTitleXml", null, utdXmlContent);
        return UniversalTransferDocumentInfoProtos.UniversalTransferDocumentSellerTitleInfo.parseFrom(response);
    }

    public UniversalTransferDocumentInfoProtos.UniversalTransferDocumentBuyerTitleInfo ParseUniversalTransferDocumentBuyerTitleXml(byte[] utdXmlContent) throws IOException
    {
        byte[] response = PerformPostHttpRequest("/ParseUniversalTransferDocumentBuyerTitleXml", null, utdXmlContent);
        return UniversalTransferDocumentInfoProtos.UniversalTransferDocumentBuyerTitleInfo.parseFrom(response);
    }

    public UniversalTransferDocumentInfoProtos.UniversalCorrectionDocumentSellerTitleInfo ParseUniversalCorrectionDocumentSellerTitleXml(byte[] utdXmlContent) throws IOException
    {
        byte[] response = PerformPostHttpRequest("/ParseUniversalCorrectionDocumentSellerTitleXml", null, utdXmlContent);
        return UniversalTransferDocumentInfoProtos.UniversalCorrectionDocumentSellerTitleInfo.parseFrom(response);
    }

    public UniversalTransferDocumentInfoProtos.UniversalTransferDocumentBuyerTitleInfo ParseUniversalCorrectionDocumentBuyerTitleXml(byte[] utdXmlContent) throws IOException
    {
        byte[] response = PerformPostHttpRequest("/ParseUniversalCorrectionDocumentBuyerTitleXml", null, utdXmlContent);
        return UniversalTransferDocumentInfoProtos.UniversalTransferDocumentBuyerTitleInfo.parseFrom(response);
    }

    public RevocationRequestInfoProtos.RevocationRequestInfo ParseRevocationRequestXml(byte[] xmlContent) throws IOException
    {
        return RevocationRequestInfoProtos.RevocationRequestInfo.parseFrom(PerformPostHttpRequest("/ParseRevocationRequestXml", null, xmlContent));
    }

    public SignatureRejectionInfoProtos.SignatureRejectionInfo ParseSignatureRejectionXml(byte[] xmlContent) throws IOException
    {
        return SignatureRejectionInfoProtos.SignatureRejectionInfo.parseFrom(PerformPostHttpRequest("/ParseSignatureRejectionXml", null, xmlContent));
    }

    public InvoiceCorrectionRequestInfoProtos.InvoiceCorrectionRequestInfo GetInvoiceCorrectionRequestInfo(String boxId, String messageId, String entityId) throws IOException {
        if (boxId == null)
            throw new NullPointerException("boxId");
        if (messageId == null)
            throw new NullPointerException("messageId");
        if (entityId == null)
            throw new NullPointerException("entityId");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        parameters.add(new BasicNameValuePair("messageId", messageId));
        parameters.add(new BasicNameValuePair("entityId", entityId));
        return InvoiceCorrectionRequestInfoProtos.InvoiceCorrectionRequestInfo.parseFrom(PerformGetHttpRequest("/GetInvoiceCorrectionRequestInfo", parameters));
    }

    public DiadocMessage_GetApiProtos.MessagePatch PostMessagePatch(DiadocMessage_PostApiProtos.MessagePatchToPost patch) throws IOException {
        if (patch == null)
            throw new NullPointerException("patch");
        return DiadocMessage_GetApiProtos.MessagePatch.parseFrom(PerformPostHttpRequest("/V3/PostMessagePatch", null, patch.toByteArray()));
    }

    public DiadocMessage_GetApiProtos.BoxEvent GetEvent(String boxId, String eventId) throws IOException {
        if (boxId == null) throw new NullPointerException("boxId");
        if (eventId == null) throw new NullPointerException("eventId");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        parameters.add(new BasicNameValuePair("eventId", eventId));
        return DiadocMessage_GetApiProtos.BoxEvent.parseFrom(PerformGetHttpRequest("/V2/GetEvent", parameters));
    }

    public PrintFormResult GeneratePrintForm(String boxId, String messageId, String documentId) throws IllegalStateException, IOException, ParseException {
        if (Tools.IsNullOrEmpty(boxId))
            throw new NullPointerException("boxId");
        if (Tools.IsNullOrEmpty(messageId))
            throw new NullPointerException("messageId");
        if (Tools.IsNullOrEmpty(documentId))
            throw new NullPointerException("documentId");

        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        parameters.add(new BasicNameValuePair("messageId", messageId));
        parameters.add(new BasicNameValuePair("documentId", documentId));
        HttpResponse webResponse = ReceiveGetHttpResponse("/GeneratePrintForm", parameters);

        try {
            if (webResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK)
                throw new IllegalStateException(webResponse.getStatusLine().toString());

            Integer retryAfter = TryGetRetryAfter(webResponse);
            if (retryAfter != null)
                return new PrintFormResult(retryAfter);

            String fileName = GetHttpResponseFileName(webResponse);
            byte[] content = GetResponseBytes(webResponse);
            String contentType = webResponse.getEntity().getContentType().getValue();
            return new PrintFormResult(new PrintFormContent(contentType, fileName, content));
        } finally {
            webResponse.getEntity().getContent().close();
        }
    }

    public DocumentProtocolResult GenerateDocumentProtocol(String boxId, String messageId, String documentId) throws IllegalStateException, IOException, ParseException {
        if (Tools.IsNullOrEmpty(boxId))
            throw new NullPointerException("boxId");
        if (Tools.IsNullOrEmpty(messageId))
            throw new NullPointerException("messageId");
        if (Tools.IsNullOrEmpty(documentId))
            throw new NullPointerException("documentId");

        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        parameters.add(new BasicNameValuePair("messageId", messageId));
        parameters.add(new BasicNameValuePair("documentId", documentId));
        HttpResponse webResponse = ReceiveGetHttpResponse("/GenerateDocumentProtocol", parameters);

        try {
            if (webResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK)
                throw new IllegalStateException(webResponse.getStatusLine().toString());

            Integer retryAfter = TryGetRetryAfter(webResponse);
            if (retryAfter != null)
                return new DocumentProtocolResult(retryAfter);

            byte[] content = GetResponseBytes(webResponse);
            return new DocumentProtocolResult(DocumentProtocolProtos.DocumentProtocol.parseFrom(content));
        } finally {
            webResponse.getEntity().getContent().close();
        }
    }

    public DocumentZipResult GenerateDocumentZip(String boxId, String messageId, String documentId, Boolean fullDocflow) throws IllegalStateException, IOException, ParseException {
        if (Tools.IsNullOrEmpty(boxId))
            throw new NullPointerException("boxId");
        if (Tools.IsNullOrEmpty(messageId))
            throw new NullPointerException("messageId");
        if (Tools.IsNullOrEmpty(documentId))
            throw new NullPointerException("documentId");

        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        parameters.add(new BasicNameValuePair("messageId", messageId));
        parameters.add(new BasicNameValuePair("documentId", documentId));
        parameters.add(new BasicNameValuePair("fullDocflow", fullDocflow.toString()));

        HttpResponse webResponse = ReceiveGetHttpResponse("/GenerateDocumentZip", parameters);

        try {
            if (webResponse.getStatusLine().getStatusCode() != HttpStatus.SC_OK)
                throw new IllegalStateException(webResponse.getStatusLine().toString());

            Integer retryAfter = TryGetRetryAfter(webResponse);
            if (retryAfter != null)
                return new DocumentZipResult(retryAfter);

            byte[] content = GetResponseBytes(webResponse);
            return new DocumentZipResult(DocumentZipProtos.DocumentZipGenerationResult.parseFrom(content));
        } finally {
            webResponse.getEntity().getContent().close();
        }
    }



    public String Recognize(String fileName, byte[] content) throws IOException {
        if (Tools.IsNullOrEmpty(fileName)) throw new NullPointerException("fileName");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("fileName", fileName));
        return new String(PerformPostHttpRequest("/Recognize", parameters, content), "UTF-8");
    }

    public RecognitionProtos.Recognized GetRecognized(String recognitionId) throws IOException {
        if (Tools.IsNullOrEmpty(recognitionId)) throw new NullPointerException("recognitionId");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("recognitionId", recognitionId));
        return RecognitionProtos.Recognized.parseFrom(PerformGetHttpRequest("/GetRecognized", parameters));
    }

    public DocumentListProtos.DocumentList GetDocuments(DocumentsFilter filter) throws IOException {
        if (filter.boxId == null) throw new NullPointerException("boxId");
        if (filter.filterCategory == null) throw new NullPointerException("filterCategory");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", filter.boxId));
        parameters.add(new BasicNameValuePair("filterCategory", filter.filterCategory));
        if (!Tools.IsNullOrEmpty(filter.counteragentBoxId)) parameters.add(new BasicNameValuePair("counteragentBoxId", filter.counteragentBoxId));
        if (filter.timestampFrom != null) {
            long fromTicks = Tools.toCsTicks(filter.timestampFrom.getTime());
            parameters.add(new BasicNameValuePair("timestampFromTicks", Long.toString(fromTicks)));
        }
        if (filter.timestampTo != null) {
            long toTicks = Tools.toCsTicks(filter.timestampTo.getTime());
            parameters.add(new BasicNameValuePair("timestampToTicks", Long.toString(toTicks)));
        }
        if (!Tools.IsNullOrEmpty(filter.fromDocumentDate))
            parameters.add(new BasicNameValuePair("fromDocumentDate", filter.fromDocumentDate));
        if (!Tools.IsNullOrEmpty(filter.toDocumentDate))
            parameters.add(new BasicNameValuePair("toDocumentDate", filter.toDocumentDate));
        if (!Tools.IsNullOrEmpty(filter.departmentId))
            parameters.add(new BasicNameValuePair("departmentId", filter.departmentId));
        if (filter.excludeSubdepartments)
            parameters.add(new BasicNameValuePair("excludeSubdepartments", null));
        if (!Tools.IsNullOrEmpty(filter.sortDirection))
            parameters.add(new BasicNameValuePair("sortDirection", filter.sortDirection));
        if (filter.afterIndexKey != null)
            parameters.add(new BasicNameValuePair("afterIndexKey", filter.afterIndexKey));
        return DocumentListProtos.DocumentList.parseFrom(PerformGetHttpRequest("/V3/GetDocuments", parameters));
    }

    public DocumentListProtos.DocumentList GetDocuments(String boxId, String filterCategory, String counteragentBoxId, Date timestampFrom, Date timestampTo, String fromDocumentDate, String toDocumentDate, String departmentId, boolean excludeSubdepartments, String afterIndexKey) throws IOException {
        DocumentsFilter documentsFilter = new DocumentsFilter();
        documentsFilter.boxId = boxId;
        documentsFilter.filterCategory = filterCategory;
        documentsFilter.counteragentBoxId = counteragentBoxId;
        documentsFilter.timestampFrom = timestampFrom;
        documentsFilter.timestampTo = timestampTo;
        documentsFilter.fromDocumentDate = fromDocumentDate;
        documentsFilter.toDocumentDate = toDocumentDate;
        documentsFilter.departmentId = departmentId;
        documentsFilter.excludeSubdepartments = excludeSubdepartments;
        documentsFilter.afterIndexKey = afterIndexKey;
        return GetDocuments(documentsFilter);
    }

    public DocumentProtos.Document GetDocument(String boxId, String messageId, String entityId) throws IOException {
        if (boxId == null) throw new NullPointerException("boxId");
        if (messageId == null) throw new NullPointerException("messageId");
        if (entityId == null) throw new NullPointerException("entityId");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        parameters.add(new BasicNameValuePair("messageId", messageId));
        parameters.add(new BasicNameValuePair("entityId", entityId));
        return DocumentProtos.Document.parseFrom(PerformGetHttpRequest("/V3/GetDocument", parameters));
    }

    public CounteragentProtos.Counteragent GetCounteragent(String myOrgId, String counteragentOrgId) throws IOException {
        if (Tools.IsNullOrEmpty(myOrgId)) throw new NullPointerException("myOrgId");
        if (Tools.IsNullOrEmpty(counteragentOrgId)) throw new NullPointerException("counteragentOrgId");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("myOrgId", myOrgId));
        parameters.add(new BasicNameValuePair("counteragentOrgId", counteragentOrgId));
        return CounteragentProtos.Counteragent.parseFrom(PerformGetHttpRequest("/V2/GetCounteragent", parameters));
    }

    public CounteragentProtos.CounteragentCertificateList GetCounteragentCertificates(String myOrgId, String counteragentOrgId) throws IOException {
        if (Tools.IsNullOrEmpty(myOrgId)) throw new NullPointerException("myOrgId");
        if (Tools.IsNullOrEmpty(counteragentOrgId)) throw new NullPointerException("counteragentOrgId");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("myOrgId", myOrgId));
        parameters.add(new BasicNameValuePair("counteragentOrgId", counteragentOrgId));
        return CounteragentProtos.CounteragentCertificateList.parseFrom(PerformGetHttpRequest("/GetCounteragentCertificates", parameters));
    }

    public CounteragentProtos.CounteragentList GetCounteragents(String myOrgId, String counteragentStatus, String afterIndexKey) throws IOException {
        if (Tools.IsNullOrEmpty(myOrgId)) throw new NullPointerException("myOrgId");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("myOrgId", myOrgId));
        if (!Tools.IsNullOrEmpty(counteragentStatus))
            parameters.add(new BasicNameValuePair("counteragentStatus", counteragentStatus));
        if (afterIndexKey != null)
            parameters.add(new BasicNameValuePair("afterIndexKey", afterIndexKey));
        return CounteragentProtos.CounteragentList.parseFrom(PerformGetHttpRequest("/V2/GetCounteragents", parameters));
    }

    public AsyncMethodResultProtos.AsyncMethodResult AcquireCounteragent(String myOrgId, AcquireCounteragentProtos.AcquireCounteragentRequest request) throws IOException {
        if (Tools.IsNullOrEmpty(myOrgId)) throw new NullPointerException("myOrgId");

        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("myOrgId", myOrgId));
        return AsyncMethodResultProtos.AsyncMethodResult.parseFrom(PerformPostHttpRequest("/V2/AcquireCounteragent", parameters, request.toByteArray()));
    }

    public AsyncMethodResultProtos.AsyncMethodResult AcquireCounteragent(String myOrgId, String myDepartmentId, AcquireCounteragentProtos.AcquireCounteragentRequest request) throws IOException {
        if (Tools.IsNullOrEmpty(myOrgId)) throw new NullPointerException("myOrgId");

        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("myOrgId", myOrgId));
        if (myDepartmentId != null)
            parameters.add(new BasicNameValuePair("myDepartmentId", myDepartmentId));
        return AsyncMethodResultProtos.AsyncMethodResult.parseFrom(PerformPostHttpRequest("/V2/AcquireCounteragent", parameters, request.toByteArray()));
    }

    public AcquireCounteragentProtos.AcquireCounteragentResult WaitAcquireCounteragentResult(String taskId, Integer timeoutInMillis) throws Exception {
        byte[] data = WaitTaskResult("/AcquireCounteragentResult", taskId, timeoutInMillis);
        return AcquireCounteragentProtos.AcquireCounteragentResult.parseFrom(data);
    }


    public void BreakWithCounteragent(String myOrgId, String counteragentOrgId, String comment) throws IOException {
        if (Tools.IsNullOrEmpty(myOrgId)) throw new NullPointerException("myOrgId");
        if (Tools.IsNullOrEmpty(counteragentOrgId)) throw new NullPointerException("counteragentOrgId");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("myOrgId", myOrgId));
        parameters.add(new BasicNameValuePair("counteragentOrgId", counteragentOrgId));
        if (!Tools.IsNullOrEmpty(comment))
            parameters.add(new BasicNameValuePair("comment", comment));
        PerformPostHttpRequest("/BreakWithCounteragent", parameters, null);
    }

    public void RecycleDraft(String boxId, String draftId) throws IOException {
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        parameters.add(new BasicNameValuePair("draftId", draftId));
        PerformPostHttpRequest("/RecycleDraft", parameters, null);
    }

    public boolean CanSendInvoice(String boxId, byte [] certBytes) throws IOException {
        if (Tools.IsNullOrEmpty(boxId)) throw new NullPointerException("boxId");
        if (certBytes == null || certBytes.length == 0) throw new NullPointerException("certBytes");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("boxId", boxId));
        HttpResponse httpResponse = ReceivePostHttpResponse("/CanSendInvoice", parameters, certBytes);
        int statusCode = httpResponse.getStatusLine().getStatusCode();
        byte[] responseContent = IOUtils.toByteArray(httpResponse.getEntity().getContent());
        switch (statusCode)
        {
            case HttpStatus.SC_OK:
                return true;
            case HttpStatus.SC_FORBIDDEN:
                return false;
            default:
                throw new HttpResponseException(statusCode, new String(responseContent,"UTF8"));
        }
    }

    public void MoveDocuments(DocumentsMoveOperationProtos.DocumentsMoveOperation operation) throws IOException{
        PerformPostHttpRequest("/MoveDocuments", new ArrayList<NameValuePair>(), operation.toByteArray());
    }

    public int GetShelfUploadChunkSize() {
        return SHELF_PART_LENGTH;
    }

    public int GetShelfUploadMaxAttemptsCount() {
        return SHELF_MAX_ATTEMPTS;
    }

    public String UploadFileToShelf(byte[] data) throws Exception {
        if (data == null)
            throw new NullPointerException("data");

        String nameOnShelf = String.format("java_api-%s", UUID.randomUUID().toString().replaceAll("-", ""));
        List<ByteArraySegment> parts = SplitDataIntoParts(data);

        List<Integer> missingParts = new ArrayList<Integer>();
        for (int i = 0; i < parts.size(); i++)
            missingParts.add(i);

        List<IOException> httpErrors = new ArrayList<IOException>();
        int attempts = 0;
        while (missingParts.size() > 0)
        {
            if (++attempts > SHELF_MAX_ATTEMPTS)
                throw new AggregateException("Reached the limit of attempts to send a file", httpErrors.toArray(new Throwable[0]));

            int partsCount = parts.size();
            int lastPartIndex = partsCount - 1;
            //always add last part for stability
            if (!missingParts.contains(lastPartIndex))
                missingParts.add(lastPartIndex);

            missingParts = PutMissingParts(nameOnShelf, parts, missingParts, partsCount, httpErrors);
        }
        return nameOnShelf;
    }

    private List<Integer> PutMissingParts(String nameOnShelf, List<ByteArraySegment> allParts, List<Integer> missingParts, int partsCount, List<IOException> httpErrors) throws Exception {
        Set<Integer> lastMissingParts = new HashSet<Integer>(missingParts);
        int maxProcessedPartIndex = -1;

        for (int partIndex: missingParts)
        {
            boolean isLastPart = partIndex == partsCount - 1;
            List<Integer> newMissingParts = PutPart(nameOnShelf, allParts.get(partIndex), partIndex, isLastPart, httpErrors);
            if (newMissingParts != null)
            {
                if (partIndex > maxProcessedPartIndex)
                {
                    lastMissingParts.clear();
                    lastMissingParts.addAll(newMissingParts);

                    maxProcessedPartIndex = partIndex;
                }
            }
            else
                lastMissingParts.add(partIndex);
        }
        if (lastMissingParts == null)
            throw new Exception("ShelfUpload did not return missing parts");
        return new ArrayList<Integer>(lastMissingParts);
    }

    private List<Integer> PutPart(String nameOnShelf, ByteArraySegment part, int partIndex, boolean isLastPart, List<IOException> httpErrors) throws IOException {
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair("nameOnShelf", String.format("__userId__/%s", nameOnShelf)));
        parameters.add(new BasicNameValuePair("partIndex", Integer.toString(partIndex)));
        if (isLastPart)
            parameters.add(new BasicNameValuePair("isLastPart", "1"));

        HttpResponse httpResponse;
        try
        {
            httpResponse = ReceivePostHttpResponse("/ShelfUpload", parameters, part.getBytes());
            if (httpResponse == null)
                throw new IOException("http response:null");
        }
        catch (IOException e)
        {
            httpErrors.add(e);
            return null;
        }

        int statusCode = httpResponse.getStatusLine().getStatusCode();

        if (statusCode != HttpStatus.SC_OK){
            if (SHELF_NON_RETRIABLE_STATUS_CODES.contains(statusCode)){
                throw new IOException(FormatResponseMessage(httpResponse, statusCode));
            }

            httpErrors.add(new IOException(FormatResponseMessage(httpResponse, statusCode)));
            return null;
        }

        byte[] responseContent = IOUtils.toByteArray(httpResponse.getEntity().getContent());
        if (responseContent == null || responseContent.length == 0)
            return null;

        String responseString = new String(responseContent, "UTF-8");

        int[] missingParts = new Gson().fromJson(responseString, int[].class);
        ArrayList<Integer> result = new ArrayList<Integer>();
        for (int item : missingParts)
            result.add(item);

        return result;
    }

    private String FormatResponseMessage(HttpResponse httpResponse, int statusCode) {
        return String.format("Status code:%s%sMessage:%s%s",
                statusCode, System7Emu.lineSeparator(), httpResponse.getStatusLine().getReasonPhrase(), System7Emu.lineSeparator());
    }

    private ArrayList<ByteArraySegment> SplitDataIntoParts(byte[] data) {
        ArrayList<ByteArraySegment> result = new ArrayList<ByteArraySegment>();
        int currentPartOffset = 0;
        while (currentPartOffset < data.length)
        {
            int length = Math.min(data.length - currentPartOffset, SHELF_PART_LENGTH);
            result.add(new ByteArraySegment(data, currentPartOffset, length));
            currentPartOffset += length;
        }
        return result;
    }

    private OrganizationProtos.Organization GetOrganization(String id, String idName) throws IOException {
        if (id == null)
            throw new NullPointerException("id");
        List<NameValuePair> parameters = new ArrayList<NameValuePair>();
        parameters.add(new BasicNameValuePair(idName, id));
        return OrganizationProtos.Organization.parseFrom(PerformGetHttpRequest("/GetOrganization", parameters));
    }

    public UserProtos.User GetMyUser() throws IOException {
        byte[] responseBytes = PerformGetHttpRequest("/GetMyUser", null);
        return UserProtos.User.parseFrom(responseBytes);
    }

    public AsyncMethodResultProtos.AsyncMethodResult CloudSign(CloudSignProtos.CloudSignRequest request) throws Exception {
        return CloudSign(request, null);
    }

    public AsyncMethodResultProtos.AsyncMethodResult CloudSign(CloudSignProtos.CloudSignRequest request, String certificateThumbprint) throws Exception {
        if (request == null) throw new NullPointerException("request");
        List<NameValuePair> params = new ArrayList<NameValuePair>();
        if (certificateThumbprint != null)
            params.add(new BasicNameValuePair("certificateThumbprint", certificateThumbprint));
        byte[] responseBytes = PerformPostHttpRequest("/CloudSign", params, request.toByteArray());
        return AsyncMethodResultProtos.AsyncMethodResult.parseFrom(responseBytes);
    }

    public CloudSignProtos.CloudSignResult WaitCloudSignResult(String taskId, Integer timeoutInMillis) throws Exception {
        byte[] data = WaitTaskResult("/CloudSignResult", taskId, timeoutInMillis);
        return CloudSignProtos.CloudSignResult.parseFrom(data);
    }

    public AsyncMethodResultProtos.AsyncMethodResult CloudSignConfirm(String token, String confirmationCode, Boolean returnContent) throws IOException
    {
        List<NameValuePair> params = new ArrayList<NameValuePair>();
        params.add(new BasicNameValuePair("token", token));
        params.add(new BasicNameValuePair("confirmationCode", confirmationCode));
        params.add(new BasicNameValuePair("return", returnContent ? "Content" : "Reference"));
        byte[] responseBytes = PerformPostHttpRequest("/CloudSignConfirm", params, null);
        return AsyncMethodResultProtos.AsyncMethodResult.parseFrom(responseBytes);
    }

    public CloudSignProtos.CloudSignConfirmResult WaitCloudSignConfirmResult(String taskId, Integer timeoutInMillis) throws Exception {
        byte[] data = WaitTaskResult("/CloudSignConfirmResult", taskId, timeoutInMillis);
        return CloudSignProtos.CloudSignConfirmResult.parseFrom(data);
    }

    public AsyncMethodResultProtos.AsyncMethodResult AutoSignReceipts(String boxId, String certificateThumbprint, String batchKey) throws Exception {
        if (boxId == null) throw new NullPointerException("boxId");
        List<NameValuePair> params = new ArrayList<NameValuePair>();
        params.add(new BasicNameValuePair("boxId", boxId));
        if (certificateThumbprint != null)
            params.add(new BasicNameValuePair("certificateThumbprint", certificateThumbprint));
        if (batchKey != null)
            params.add(new BasicNameValuePair("batchKey", batchKey));
        byte[] responseBytes = PerformPostHttpRequest("/AutoSignReceipts", params, null);
        return AsyncMethodResultProtos.AsyncMethodResult.parseFrom(responseBytes);
    }

    public CloudSignProtos.AutosignReceiptsResult WaitAutosignReceiptsResult(String taskId, Integer timeoutInMillis)
            throws Exception {
        byte[] data = WaitTaskResult("/AutosignReceiptsResult", taskId, timeoutInMillis);
        return CloudSignProtos.AutosignReceiptsResult.parseFrom(data);
    }

    public DocumentListProtos.DocumentList GetDocumentsByMessageId(String boxId, String messageId) throws IOException {
        List<NameValuePair> params = new ArrayList<NameValuePair>();
        params.add(new BasicNameValuePair("boxId", boxId));
        params.add(new BasicNameValuePair("messageId", messageId));
        byte[] bytes = PerformGetHttpRequest("/GetDocumentsByMessageId", params);
        return DocumentListProtos.DocumentList.parseFrom(bytes);
    }

    public DocflowApiProtos.SearchDocflowsResponse SearchDocflows(String boxId, DocflowApiProtos.SearchDocflowsRequest request) throws IOException {
        List<NameValuePair> params = new ArrayList<NameValuePair>();
        params.add(new BasicNameValuePair("boxId", boxId));
        byte[] body = request.toByteArray();
        byte[] bytes = PerformPostHttpRequest("/SearchDocflows", params, body);
        return DocflowApiProtos.SearchDocflowsResponse.parseFrom(bytes);
    }

    public DiadocMessage_PostApiProtos.PrepareDocumentsToSignResponse PrepareDocumentsToSign(DiadocMessage_PostApiProtos.PrepareDocumentsToSignRequest request) throws IOException {
        byte[] body = request.toByteArray();
        byte[] bytes = PerformPostHttpRequest("/PrepareDocumentsToSign", null, body);
        return DiadocMessage_PostApiProtos.PrepareDocumentsToSignResponse.parseFrom(bytes);
    }

    public static int WaitTaskDefaultTimeoutInMillis = 5 * 60 * 1000;

    protected byte[] WaitTaskResult(String url, String taskId, Integer timeoutInMillis) throws Exception  {
        List<NameValuePair> params = new ArrayList<NameValuePair>();
        params.add(new BasicNameValuePair("taskId", taskId));

        if (timeoutInMillis == null)
            timeoutInMillis = WaitTaskDefaultTimeoutInMillis;
        long timeLimit = new Date(new Date().getTime() + timeoutInMillis).getTime();
        while(true) {

            HttpResponse webResponse = ReceiveGetHttpResponse(url, params);
            int statusCode = webResponse.getStatusLine().getStatusCode();
            if (statusCode == HttpStatus.SC_NO_CONTENT) {
                if (new Date().getTime() > timeLimit) {
                    throw new TimeoutException(String.format("Can't GET '%s'. Timeout %ds expired.", url, timeLimit / 1000));
                }
                Integer retryAfter = TryGetRetryAfter(webResponse);
                int delayInSeconds = retryAfter != null
                        ? Math.min(retryAfter.intValue(), 15)
                        : 15;
                Thread.sleep(delayInSeconds * 1000);
                continue;
            }
            if (statusCode != HttpStatus.SC_OK){
                throw new DiadocErrorException(webResponse.getStatusLine().getReasonPhrase(), statusCode, TryGetDiadocErrorCode(webResponse));
            }
            return GetResponseBytes(webResponse);
        }
    }

    private static Integer TryGetRetryAfter(HttpResponse webResponse){
        Header[] retryAfterHeaders = webResponse.getHeaders(HttpHeaders.RETRY_AFTER);
        if (retryAfterHeaders.length > 0) {
            String retryAfterStr = retryAfterHeaders[0].getValue();
            if (!Tools.IsNullOrEmpty(retryAfterStr)) {
                return Integer.parseInt(retryAfterStr);
            }
        }
        return null;
    }

    private static String TryGetDiadocErrorCode(HttpResponse webResponse){
        Header[] errorCodeHeaders = webResponse.getHeaders("X-Diadoc-ErrorCode");
        if (errorCodeHeaders.length > 0) {
            return errorCodeHeaders[0].getValue();
        }
        return null;
    }
}
