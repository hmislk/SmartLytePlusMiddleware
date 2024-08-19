package org.carecode.lims.mw;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import org.apache.logging.log4j.Logger;
import org.carecode.lims.libraries.PatientDataBundle;
import org.carecode.lims.libraries.ResultsRecord;
import org.carecode.lims.libraries.PatientRecord;
import org.carecode.lims.libraries.MiddlewareSettings;

public class LISCommunicator {

    private final Logger logger;
    private static final Gson gson = new Gson();
    private final MiddlewareSettings middlewareSettings;

    public LISCommunicator(Logger logger, MiddlewareSettings settings) {
        this.logger = logger;
        this.middlewareSettings = settings;
    }

    public Map<String, String> parseQueryParams(String query) {
        Map<String, String> params = new HashMap<>();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            if (idx != -1) {
                params.put(pair.substring(0, idx), pair.substring(idx + 1));
            }
        }
        return params;
    }

    // Parses the ion-specific data from the parameters
    public Map<String, String> parseIonData(Map<String, String> params, String ion) {
        Map<String, String> ionData = new HashMap<>();
        String prefix = "ionData[" + ion + "]";
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                try {
                    // Correctly extracting the substring between brackets
                    String keySegment = entry.getKey().substring(entry.getKey().indexOf('['), entry.getKey().lastIndexOf(']') + 1);
                    String subKey = keySegment.replaceAll("\\[|\\]", "").replace(ion, "").replaceAll("^\\.", ""); // Remove leading dots if any
                    if (!subKey.isEmpty()) {
                        ionData.put(subKey, entry.getValue());
                    }
                } catch (StringIndexOutOfBoundsException e) {
                    logger.error("Error parsing ion data for key: " + entry.getKey(), e);
                }
            }
        }
        return ionData;
    }

    
    // Method to extract ion-specific data
    public Map<String, String> parseIonData(Map<String, String> params, String ion, boolean otherMethod) {
        Map<String, String> ionData = new HashMap<>();
        String prefix = "ionData[" + ion + "]";
        params.forEach((key, value) -> {
            if (key.startsWith(prefix)) {
                try {
                    String subKey = key.substring(key.indexOf('['), key.lastIndexOf(']') + 1);
                    subKey = subKey.replaceAll("\\[|\\]", "").replace(ion, "").replaceAll("^\\.", ""); // Remove leading dots if any
                    if (!subKey.isEmpty()) {
                        ionData.put(subKey, value);
                    }
                } catch (StringIndexOutOfBoundsException e) {
                    logger.error("Error parsing ion data for key: " + key, e);
                }
            }
        });
        return ionData;
    }
    
    public PatientDataBundle createPatientDataBundleFromParams(Map<String, String> params) {
        PatientDataBundle pdb = new PatientDataBundle();

        // Assuming patient record parsing is already handled
        PatientRecord patientRecord = new PatientRecord(
                0, // frameNumber
                params.getOrDefault("pId", "defaultPatientId"),
                null, // additionalId
                "Test Patient", // patientName if not provided
                null, // patientSecondName
                null, // patientSex
                null, // race
                null, // dob
                null, // patientAddress
                null, // patientPhoneNumber
                null // attendingDoctor
        );
        pdb.setPatientRecord(patientRecord);

        // List of expected ions based on your data structure
        String[] expectedIons = {"Na", "K", "Cl", "Ca"};

        for (String ion : expectedIons) {
            if (params.containsKey("ionData[" + ion + "][ion]")) { // Check if the ion data is present
                Map<String, String> ionData = parseIonData(params, ion);
                if (!ionData.isEmpty()) {
                    ResultsRecord resultsRecord = new ResultsRecord(
                            0, // frameNumber, adjust as necessary
                            ion, // Test Code, using ion as a test code
                            Double.parseDouble(ionData.getOrDefault("conc", "0")), // Result Value, providing default if missing
                            Double.parseDouble(ionData.getOrDefault("min", "0")), // Minimum Value
                            Double.parseDouble(ionData.getOrDefault("max", "0")), // Maximum Value
                            ionData.getOrDefault("flag", ""), // Flag
                            ionData.getOrDefault("sampleType", ""), // Sample Type
                            ionData.getOrDefault("strUnits", "unit"), // Result Units
                            null, // Result DateTime, if applicable
                            null, // Instrument Name, if applicable
                            params.get("pId") // Using patient ID as sample ID
                    );

                    pdb.addResultsRecord(resultsRecord);
                } else {
                    logger.warn("No data found for ion: " + ion);
                }
            }
        }

        return pdb;
    }



    public void pushResults(PatientDataBundle patientDataBundle) {
        try {
            String pushResultsEndpoint = middlewareSettings.getLimsSettings().getLimsServerBaseUrl() + "/test_results";
            URL url = new URL(pushResultsEndpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);

            String jsonInputString = gson.toJson(patientDataBundle);
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonInputString.getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            try (BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                String inputLine;
                StringBuilder response = new StringBuilder();
                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                logger.info("Response from server: " + response.toString());
            }
        } catch (Exception e) {
            logger.error("Failed to send results to LIMS", e);
        }
    }
}
