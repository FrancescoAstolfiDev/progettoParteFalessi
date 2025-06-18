package project.controllers;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONTokener;
import project.models.Release;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import project.models.Ticket;



public class JiraInfoRetrieve {
    private static final Logger LOGGER = Logger.getLogger(JiraInfoRetrieve.class.getName());
    private static final int MAX_RETRIES = 5;  // Increased from 3 to 5
    private static final int CONNECTION_TIMEOUT = 60000;  // Increased to 60 seconds
    private static final int READ_TIMEOUT = 60000;  // Increased to 60 seconds
    private static final int RETRY_DELAY_SECONDS = 10;  // Increased delay between retries

    private final String projKey;
    private final List<Ticket> ticketsWithValidAV;

    public JiraInfoRetrieve(String projName) {
        this.projKey = projName.toUpperCase();
        this.ticketsWithValidAV = new ArrayList<>();
    }

    public List<Ticket> getTicketsWithValidAV() {
        return this.ticketsWithValidAV;
    }

    public List<Ticket> retrieveTickets(List<Release> releasesList) throws IOException, ParseException {
        List<Ticket> allTickets = new ArrayList<>();
        JSONObject jsonObject = getInfoFromJiraWithRetry(1000, 0);
        JSONArray issues = jsonObject.getJSONArray("issues");
        int total = jsonObject.getInt("total");
        int counter = 0;

        if (total <= issues.length()) {
            return getTickets(issues, releasesList);
        } else {
            do {
                allTickets.addAll(getTickets(issues, releasesList));
                if (counter <= total) {
                    counter += 1000;
                    jsonObject = getInfoFromJiraWithRetry(1000, counter);
                    issues = jsonObject.getJSONArray("issues");
                    total = jsonObject.getInt("total");
                }
            } while (counter <= total);
        }
        return allTickets;
    }

    private List<Ticket> getTickets(JSONArray issues, List<Release> releasesList) throws ParseException {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        List<Ticket> tickets = new ArrayList<>();

        for (int i = 0; i < issues.length(); i++) {
            JSONObject issue = issues.getJSONObject(i);
            String key = issue.getString("key");
            JSONObject fields = issue.getJSONObject("fields");

            String resolutionDateString = fields.getString("resolutiondate");
            String creationDateString = fields.getString("created");
            JSONArray av = fields.getJSONArray("versions");
            // Estrazione del tipo di ticket
            String issueType = fields.getJSONObject("issuetype").getString("name");


            Date resolutionDate = formatter.parse(resolutionDateString);
            Date creationDate = formatter.parse(creationDateString);

            Release creationRelease = getReleaseFromDate(releasesList, creationDate);
            Release resolutionRelease = getReleaseFromDate(releasesList, resolutionDate);
            if (creationRelease == null || resolutionRelease == null) continue;

            Date firstDate = null;
            if (av.length() > 0) {
                firstDate = validateAV(resolutionRelease, creationRelease, av);
            }

            Ticket ticket;
            if (firstDate != null && creationRelease.getDate().before(resolutionRelease.getDate())) {
                Release corrRelease = getReleaseFromDate(releasesList, firstDate);
                ticket = new Ticket(key, creationRelease, resolutionRelease, corrRelease);
                this.ticketsWithValidAV.add(ticket);
            } else {
                ticket = new Ticket(key, creationRelease, resolutionRelease, null);
            }

            tickets.add(ticket);
        }
        return tickets;
    }

    private Date validateAV(Release resolution, Release creation, JSONArray av) throws ParseException {
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
        Date firstDate = null;

        for (int i = 0; i < av.length(); i++) {
            JSONObject avElem = av.getJSONObject(i);
            if (avElem.getBoolean("released")) {
                try {
                    String releaseDateString = avElem.getString("releaseDate");
                    Date releaseDate = formatter.parse(releaseDateString);
                    if (releaseDate.before(resolution.getDate()) &&
                            (firstDate == null || releaseDate.before(firstDate))) {
                        firstDate = releaseDate;
                    }
                } catch (JSONException e) {
                    continue;
                }
            }
        }
        return (firstDate != null && creation.getDate().after(firstDate)) ? firstDate : null;
    }

    private Release getReleaseFromDate(List<Release> list, Date date) {
        if (list.isEmpty()) return null;

        if (date.before(list.get(0).getDate()) || date.equals(list.get(0).getDate())) {
            return list.get(0);
        }
        if (date.after(list.get(list.size()-1).getDate())) {
            return null;
        }

        for (int i = 0; i < list.size()-1; i++) {
            if (date.equals(list.get(i).getDate())) {
                return list.get(i);
            }
            if (date.after(list.get(i).getDate()) && date.before(list.get(i+1).getDate())) {
                return list.get(i+1);
            }
        }
        return null;
    }

    private JSONObject getInfoFromJiraWithRetry(int numResults, int startAt) throws IOException {
        int retryCount = 0;
        IOException lastException = null;

        while (retryCount < MAX_RETRIES) {
            try {
                LOGGER.info(String.format("Attempting JIRA connection (Attempt %d/%d)",
                        retryCount + 1, MAX_RETRIES));
                return getInfoFromJira(numResults, startAt);
            } catch (IOException e) {
                lastException = e;
                retryCount++;
                LOGGER.log(Level.WARNING, String.format("Attempt %d failed. Waiting %d seconds before retry...",
                        retryCount, RETRY_DELAY_SECONDS), e);

                if (retryCount < MAX_RETRIES) {
                    try {
                        TimeUnit.SECONDS.sleep(RETRY_DELAY_SECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted during retry", ie);
                    }
                }
            }
        }

        LOGGER.log(Level.SEVERE, "All connection attempts failed", lastException);
        throw new IOException(String.format("Failed after %d attempts to connect to JIRA", MAX_RETRIES), lastException);
    }

    private JSONObject getInfoFromJira(int numResults, int startAt) throws IOException {
        // URL encode the JQL query parameters
        String jql = String.format("project=\"%s\" AND issueType=\"Bug\" AND (status=\"closed\" OR status=\"resolved\") AND resolution=\"fixed\"",
                this.projKey);
        String encodedJql = java.net.URLEncoder.encode(jql, "UTF-8");

        String urlString = String.format(
                "https://issues.apache.org/jira/rest/api/2/search?jql=%s&fields=key,resolutiondate,versions,created,issuetype&startAt=%d&maxResults=%d",
                encodedJql, startAt, numResults
        );

        LOGGER.info("Connecting to: " + urlString);
        URL url = new URL(urlString);
        URLConnection connection = url.openConnection();

        // Set timeouts and request properties
        connection.setConnectTimeout(CONNECTION_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0");

        try (InputStream inputStream = connection.getInputStream()) {
            JSONTokener tokener = new JSONTokener(inputStream);
            return new JSONObject(tokener);
        } catch (IOException e) {
            // Get more detailed error information if available
            if (connection instanceof HttpURLConnection) {
                HttpURLConnection httpConn = (HttpURLConnection) connection;
                String errorResponse = "";
                try (InputStream errorStream = httpConn.getErrorStream()) {
                    if (errorStream != null) {
                        errorResponse = new String(errorStream.readAllBytes());
                    }
                }
                LOGGER.log(Level.SEVERE, "JIRA API Error Response: " + errorResponse, e);
            }
            throw e;
        }
    }

    public List<Release> retrieveReleases() throws JSONException, IOException, ParseException {
        List<Release> allRelease = new ArrayList<>();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");

        String urlString = "https://issues.apache.org/jira/rest/api/latest/project/" + projKey + "/version";
        URL url = new URL(urlString);
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(CONNECTION_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);

        try (InputStream inputStream = connection.getInputStream()) {
            JSONTokener tokener = new JSONTokener(inputStream);
            JSONObject json = new JSONObject(tokener);
            JSONArray values = json.getJSONArray("values");

            for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.getJSONObject(i);
                if (value.getBoolean("released")) {
                    try {
                        String name = value.getString("name");
                        String date = value.getString("releaseDate");
                        allRelease.add(new Release(-1, name, formatter.parse(date)));
                    } catch (JSONException e) {
                        continue;
                    }
                }
            }
        }
        sortReleaseList(allRelease);
        return allRelease;
    }

    public void sortReleaseList(List<Release> list) {
        Collections.sort(list, (a, b) -> a.getDate().compareTo(b.getDate()));
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setId(i + 1);
        }
    }

    public void assignTicketToRelease(List<Release> releaseList, List<Ticket> allTicket) {
        for (int i = releaseList.size() - 1; i >= 0; i--) {
            int releaseId = releaseList.get(i).getId();
            List<Ticket> releaseTickets = new ArrayList<>();
            for (Ticket ticket : allTicket) {
                if (ticket.getFv().getId() <= releaseId) {
                    releaseTickets.add(ticket);
                }
            }
            releaseList.get(i).setAllReleaseTicket(releaseTickets);
        }
    }
}