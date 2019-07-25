package org.icatproject.icat_oaipmh;

import java.io.StringReader;
import java.net.URISyntaxException;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonReader;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.servlet.http.HttpServletRequest;

import org.icatproject.icat.client.ICAT;
import org.icatproject.icat.client.IcatException;
import org.icatproject.icat.client.Session;
import org.icatproject.icat.client.IcatException.IcatExceptionType;
import org.icatproject.icat_oaipmh.exceptions.InternalException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

public class ResponseBuilder {

    private static final Logger logger = LoggerFactory.getLogger(ResponseBuilder.class);

    private Session icatSession;
    private final String icatUrl;
    private final String[] icatAuth;
    private final String repositoryName;
    private final ArrayList<String> adminEmails;
    private final String requestUrl;
    private HashMap<String, MetadataFormat> metadataFormats;
    private HashMap<String, DataConfiguration> dataConfigurations;

    public ResponseBuilder(String icatUrl, String[] icatAuth, String repositoryName, ArrayList<String> adminEmails,
            String requestUrl) {
        metadataFormats = new HashMap<String, MetadataFormat>();
        dataConfigurations = new HashMap<String, DataConfiguration>();
        this.icatUrl = icatUrl;
        this.icatAuth = icatAuth;
        this.repositoryName = repositoryName;
        this.adminEmails = adminEmails;
        this.requestUrl = requestUrl;
    }

    public void addMetadataFormat(String identifier, MetadataFormat metadataFormat) {
        metadataFormats.put(identifier, metadataFormat);
    }

    public void addDataConfiguration(String identifier, DataConfiguration dataConfiguration) {
        dataConfigurations.put(identifier, dataConfiguration);
    }

    public Map<String, MetadataFormat> getMetadataFormats() {
        return metadataFormats;
    }

    public void loginIcat() throws InternalException {
        ICAT restIcat = null;
        try {
            restIcat = new ICAT(icatUrl);
        } catch (URISyntaxException e) {
            logger.error(e.getMessage());
            throw new InternalException();
        }

        HashMap<String, String> credentials = new HashMap<String, String>();
        for (int i = 1; i < icatAuth.length; i += 2) {
            credentials.put(icatAuth[i], icatAuth[i + 1]);
        }

        try {
            icatSession = restIcat.login(icatAuth[0], credentials);
        } catch (IcatException e) {
            logger.error(e.getMessage());
            throw new InternalException();
        }
    }

    public String queryIcat(String query) throws InternalException {
        try {
            return icatSession.search(query);
        } catch (IcatException e) {
            if (e.getType().equals(IcatExceptionType.SESSION)) {
                this.loginIcat();
                return queryIcat(query);
            } else {
                logger.error(e.getMessage());
                throw new InternalException();
            }
        }
    }

    public void buildIdentifyResponse(HttpServletRequest req, XmlResponse res) throws InternalException {
        HashMap<String, String> singleProperties = new HashMap<String, String>();
        HashMap<String, ArrayList<String>> repeatedProperties = new HashMap<String, ArrayList<String>>();

        String earliestDatestamp = "1000-01-01T00:00:00Z";
        OffsetDateTime earliestDateTime = OffsetDateTime.MAX;

        for (DataConfiguration dataConfiguration : dataConfigurations.values()) {
            String query = String.format("SELECT a.modTime FROM %s a ORDER BY a.modTime",
                    dataConfiguration.getMainObject());
            String result = queryIcat(query);
            JsonReader jsonReader = Json.createReader(new StringReader(result));
            JsonArray jsonArray = jsonReader.readArray();
            jsonReader.close();
            try {
                String earliestString = jsonArray.getJsonString(0).getString();
                OffsetDateTime earliest = OffsetDateTime.parse(earliestString);
                if (earliest.compareTo(earliestDateTime) < 0) {
                    earliestDateTime = earliest;
                    earliestDatestamp = IcatQueryParameters.makeFormattedDateTime(earliestString);
                }
            } catch (IndexOutOfBoundsException e) {
                logger.warn("No objects of type " + dataConfiguration.getMainObject() + " exist in ICAT");
            } catch (DateTimeException e) {
                logger.error(e.getMessage());
                throw new InternalException();
            }
        }

        singleProperties.put("repositoryName", repositoryName);
        singleProperties.put("baseURL", requestUrl);
        singleProperties.put("protocolVersion", "2.0");
        singleProperties.put("earliestDatestamp", earliestDatestamp);
        singleProperties.put("deletedRecord", "no");
        singleProperties.put("granularity", "YYYY-MM-DDThh:mm:ssZ");

        repeatedProperties.put("adminEmail", adminEmails);
        XmlInformation info = new XmlInformation(singleProperties, repeatedProperties, null);

        res.addXmlInformation(info, "Identify", null);
    }

    public void buildListIdentifiersResponse(HttpServletRequest req, XmlResponse res) throws InternalException {
        IcatQueryParameters parameters = getIcatQueryParameters(req, res);

        if (parameters != null) {
            IcatQueryResults results = getIcatRecords(parameters, false);

            Element listIdentifiers = null;
            if (results.getResults().isEmpty()) {
                res.addError("noRecordsMatch",
                        "The combination of the values of the from, until, and set arguments results in an empty list");
            } else {
                listIdentifiers = res.addRecordInformation(results.getResults(), "ListIdentifiers", false);

                int size = results.getSize();
                int cursor = results.getCursor();
                if (results.getIncomplete()) {
                    String resumptionToken = parameters.makeResumptionToken();
                    res.addResumptionToken(listIdentifiers, resumptionToken, size, cursor);
                } else {
                    res.addResumptionToken(listIdentifiers, "", size, cursor);
                }
            }
        }
    }

    public void buildListRecordsResponse(HttpServletRequest req, XmlResponse res) throws InternalException {
        IcatQueryParameters parameters = getIcatQueryParameters(req, res);

        if (parameters != null) {
            IcatQueryResults results = getIcatRecords(parameters, true);

            Element listRecords = null;
            if (results.getResults().isEmpty()) {
                res.addError("noRecordsMatch",
                        "The combination of the values of the from, until, and set arguments results in an empty list");
            } else {
                listRecords = res.addRecordInformation(results.getResults(), "ListRecords", true);

                int size = results.getSize();
                int cursor = results.getCursor();
                if (results.getIncomplete()) {
                    String resumptionToken = parameters.makeResumptionToken();
                    res.addResumptionToken(listRecords, resumptionToken, size, cursor);
                } else {
                    res.addResumptionToken(listRecords, "", size, cursor);
                }
            }
        }
    }

    public void buildListSetsResponse(HttpServletRequest req, XmlResponse res) {
        res.addError("noSetHierarchy", "This repository does not support sets");
    }

    public void buildListMetadataFormatsResponse(HttpServletRequest req, XmlResponse res) throws InternalException {
        IcatQueryParameters parameters = getIcatQueryParameters(req, res);

        if (parameters != null) {
            IcatQueryResults result = getIcatRecords(parameters, false);

            if (result.getResults().isEmpty()) {
                res.addError("idDoesNotExist",
                        "Identifier '" + req.getParameter("identifier") + "' is unknown or illegal in this repository");
            } else {
                boolean listAllMetadataFormats = true;
                DataConfiguration dataConfiguration = null;

                String dataConfigurationIdentifier = parameters.GetIdentifierDataConfiguration();
                if (dataConfigurationIdentifier != null) {
                    listAllMetadataFormats = false;
                    dataConfiguration = dataConfigurations.get(dataConfigurationIdentifier);
                }

                HashMap<String, ArrayList<XmlInformation>> informationLists = new HashMap<String, ArrayList<XmlInformation>>();
                ArrayList<XmlInformation> metadataFormatInfo = new ArrayList<XmlInformation>();

                for (Map.Entry<String, MetadataFormat> format : metadataFormats.entrySet()) {
                    if (!listAllMetadataFormats && !dataConfiguration.getMetadataPrefixes().contains(format.getKey()))
                        continue;

                    HashMap<String, String> singleProperties = new HashMap<String, String>();

                    singleProperties.put("metadataPrefix", format.getKey());
                    singleProperties.put("metadataNamespace", format.getValue().getMetadataNamespace());
                    singleProperties.put("schema", format.getValue().getMetadataSchema());

                    metadataFormatInfo.add(new XmlInformation(singleProperties, null, null));
                }

                informationLists.put("metadataFormat", metadataFormatInfo);

                XmlInformation info = new XmlInformation(null, null, informationLists);
                res.addXmlInformation(info, "ListMetadataFormats", null);
            }
        }
    }

    public void buildGetRecordResponse(HttpServletRequest req, XmlResponse res) throws InternalException {
        IcatQueryParameters parameters = getIcatQueryParameters(req, res);

        if (parameters != null) {
            String metadataPrefix = parameters.getMetadataPrefix();
            String dataConfigurationIdentifier = parameters.GetIdentifierDataConfiguration();
            DataConfiguration dataConfiguration = dataConfigurations.get(dataConfigurationIdentifier);

            if (!dataConfiguration.getMetadataPrefixes().contains(metadataPrefix)) {
                res.addError("cannotDisseminateFormat", "'" + metadataPrefix + "' is not supported by the item");
            } else {
                IcatQueryResults result = getIcatRecords(parameters, true);

                if (result.getResults().isEmpty()) {
                    res.addError("idDoesNotExist", "Identifier '" + req.getParameter("identifier")
                            + "' is unknown or illegal in this repository");
                } else {
                    res.addRecordInformation(result.getResults(), "GetRecord", true);
                }
            }
        }
    }

    private IcatQueryParameters getIcatQueryParameters(HttpServletRequest req, XmlResponse res)
            throws InternalException {
        IcatQueryParameters parameters = null;

        String resumptionToken = req.getParameter("resumptionToken");
        if (resumptionToken != null) {
            try {
                parameters = new IcatQueryParameters(resumptionToken);
            } catch (DateTimeException | IllegalArgumentException e) {
                res.addError("badArgument", "The request includes arguments with illegal values or syntax");
            } catch (InternalException e) {
                res.addError("badResumptionToken", "The value of the resumptionToken argument is invalid");
            }
        } else if (req.getParameter("set") != null) {
            res.addError("noSetHierarchy", "This repository does not support sets");
        } else {
            String metadataPrefix = req.getParameter("metadataPrefix");
            String identifier = req.getParameter("identifier");
            String from = req.getParameter("from");
            String until = req.getParameter("until");
            try {
                parameters = new IcatQueryParameters(metadataPrefix, 0, from, until, identifier,
                        dataConfigurations.keySet());
            } catch (DateTimeException | IllegalArgumentException e) {
                res.addError("badArgument", "The request includes arguments with illegal values or syntax");
            } catch (InternalException e) {
                res.addError("idDoesNotExist",
                        "Identifier '" + identifier + "' is unknown or illegal in this repository");
            }
        }

        return parameters;
    }

    public IcatQueryResults getIcatRecords(IcatQueryParameters parameters, boolean includeMetadata)
            throws InternalException {
        List<RecordInformation> records = new ArrayList<RecordInformation>();

        for (Map.Entry<String, DataConfiguration> config : dataConfigurations.entrySet()) {
            String dataConfigurationIdentifier = config.getKey();
            DataConfiguration dataConfiguration = config.getValue();

            if (parameters.GetIdentifierDataConfiguration() != null)
                if (!parameters.GetIdentifierDataConfiguration().equals(dataConfigurationIdentifier))
                    continue;

            if (parameters.getMetadataPrefix() != null)
                if (!dataConfiguration.getMetadataPrefixes().contains(parameters.getMetadataPrefix()))
                    continue;

            String mainObject = dataConfiguration.getMainObject();
            String includes = dataConfiguration.getIncludedObjects();
            String where = parameters.makeWhereCondition();
            String query = String.format("SELECT a FROM %s a %s ORDER BY a.modTime %s", mainObject, where, includes);

            String result = queryIcat(query);
            JsonReader jsonReader = Json.createReader(new StringReader(result));
            JsonArray resultsArray = jsonReader.readArray();
            jsonReader.close();

            for (JsonValue data : resultsArray) {
                XmlInformation header = extractHeaderInformation(data, dataConfigurationIdentifier,
                        dataConfiguration.getRequestedProperties());

                XmlInformation metadata = null;
                if (includeMetadata)
                    metadata = extractMetadataInformation(data, dataConfigurationIdentifier,
                            dataConfiguration.getRequestedProperties()).get(0);

                records.add(new RecordInformation(dataConfigurationIdentifier, header, metadata));
            }
        }

        boolean incomplete = false;
        List<RecordInformation> results = null;
        try {
            results = records.subList(parameters.getOffset(), records.size());
        } catch (IllegalArgumentException e) {
            throw new InternalException();
        }
        if (results.size() > parameters.getMaxResults()) {
            incomplete = true;
            int limit = parameters.getMaxResults();
            if (limit > results.size())
                limit = results.size();
            results = results.subList(0, limit);
        }

        return new IcatQueryResults(results, incomplete, records.size(), parameters.getOffset());
    }

    private XmlInformation extractHeaderInformation(JsonValue data, String dataConfigurationIdentifier,
            RequestedProperties requestedProperties) throws InternalException {
        HashMap<String, String> singleProperties = new HashMap<String, String>();

        JsonObject icatObject = ((JsonObject) data).getJsonObject(requestedProperties.getIcatObject());

        String id = icatObject.get("id").toString();
        String modTime = icatObject.getString("modTime", null);

        singleProperties.put("identifier", IcatQueryParameters.makeUniqueIdentifier(dataConfigurationIdentifier, id));
        singleProperties.put("datestamp", IcatQueryParameters.makeFormattedDateTime(modTime));

        XmlInformation headers = new XmlInformation(singleProperties, null, null);
        return headers;
    }

    private ArrayList<XmlInformation> extractMetadataInformation(JsonValue data, String dataConfigurationIdentifier,
            RequestedProperties requestedProperties) throws InternalException {
        ArrayList<XmlInformation> result = new ArrayList<XmlInformation>();

        HashMap<String, String> singleProperties = new HashMap<String, String>();
        HashMap<String, ArrayList<XmlInformation>> informationLists = new HashMap<String, ArrayList<XmlInformation>>();

        JsonValue icatObject = ((JsonObject) data).get(requestedProperties.getIcatObject());
        if (icatObject == null) {
            return result;
        }

        if (icatObject.getValueType() == ValueType.ARRAY) {
            JsonArray jsonArray = (JsonArray) icatObject;

            ArrayList<XmlInformation> elementsInfos = new ArrayList<XmlInformation>();
            for (JsonValue element : jsonArray) {
                HashMap<String, String> elementSingleProperties = new HashMap<String, String>();
                HashMap<String, ArrayList<XmlInformation>> elementInformationLists = new HashMap<String, ArrayList<XmlInformation>>();

                for (String prop : requestedProperties.getStringProperties()) {
                    String value = ((JsonObject) element).getString(prop, null);
                    if (value != null)
                        elementSingleProperties.put(prop, value);
                }

                for (String prop : requestedProperties.getNumericProperties()) {
                    JsonValue value = ((JsonObject) element).get(prop);
                    if (value != null)
                        elementSingleProperties.put(prop, value.toString());
                }

                for (String prop : requestedProperties.getDateProperties()) {
                    String value = ((JsonObject) element).getString(prop, null);
                    if (value != null)
                        elementSingleProperties.put(prop, IcatQueryParameters.makeFormattedDateTime(value));
                }

                for (RequestedProperties requestedSubProperties : requestedProperties.getSubPropertyLists()) {
                    ArrayList<XmlInformation> subInfo = extractMetadataInformation(element, dataConfigurationIdentifier,
                            requestedSubProperties);
                    elementInformationLists.put(requestedSubProperties.getIcatObject(), subInfo);
                }

                if (elementSingleProperties.size() != 0 || elementInformationLists.size() != 0)
                    elementsInfos.add(new XmlInformation(elementSingleProperties, null, elementInformationLists));
            }
            informationLists.put("instance", elementsInfos);
        } else {
            JsonObject jsonObject = (JsonObject) icatObject;

            for (String prop : requestedProperties.getStringProperties()) {
                String value = jsonObject.getString(prop, null);
                if (value != null)
                    singleProperties.put(prop, value);
            }

            for (String prop : requestedProperties.getNumericProperties()) {
                JsonValue value = jsonObject.get(prop);
                if (value != null)
                    singleProperties.put(prop, value.toString());
            }

            for (String prop : requestedProperties.getDateProperties()) {
                String value = jsonObject.getString(prop, null);
                if (value != null)
                    singleProperties.put(prop, IcatQueryParameters.makeFormattedDateTime(value));
            }

            for (RequestedProperties requestedSubProperties : requestedProperties.getSubPropertyLists()) {
                ArrayList<XmlInformation> info = extractMetadataInformation(jsonObject, dataConfigurationIdentifier,
                        requestedSubProperties);
                informationLists.put(requestedSubProperties.getIcatObject(), info);
            }
        }

        result.add(new XmlInformation(singleProperties, null, informationLists));
        return result;
    }

    public String getRequestUrl() {
        return requestUrl;
    }
}