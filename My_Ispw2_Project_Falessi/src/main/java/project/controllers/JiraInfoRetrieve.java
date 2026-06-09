package project.controllers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import project.models.Release;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import project.models.Ticket;



public class JiraInfoRetrieve {

    private String projKey;
    private List<Ticket> ticketsWithValidAV;

    public JiraInfoRetrieve(String projName) {
        this.projKey = projName.toUpperCase();
        this.ticketsWithValidAV = new ArrayList<>();
    }

    public List<Ticket> getTicketsWithValidAV(){
        return this.ticketsWithValidAV;
    }

    //FROM JIRA I RETRIEVE THE AFFECTED VERSION, THE FIXED VERSION, THE RESOLUTION DATE, THE CREATION DATE AND THE KEY
    public List<Ticket> retrieveTickets(List<Release> releasesList) throws IOException, ParseException {

        List<Ticket> allTickets = new ArrayList<>();
        JSONObject jsonObject = getInfoFromJira(1000,0);
        JSONArray issues = jsonObject.getJSONArray("issues");
        int total = jsonObject.getInt("total");
        int counter = 0;

        if(total <= issues.length()){
            return getTickets(issues,releasesList);
        }
        else{
            do {
                allTickets.addAll(getTickets(issues, releasesList));
                if (counter <= total) {
                    counter = counter+1000;
                    jsonObject = getInfoFromJira(1000, counter);
                    issues = jsonObject.getJSONArray("issues");
                    total = jsonObject.getInt("total");
                }
            }
            while(counter <= total);
        }
        return allTickets;

    }

    private List<Ticket> getTickets(JSONArray issues, List<Release> releasesList) throws ParseException {

        int issueLen = issues.length();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
        List<Ticket> allTickets = new ArrayList<>();

        for (int i = 0; i < issueLen; i++) {

            //get the i-th ticket
            JSONObject issue = issues.getJSONObject(i);

            //get the key
            String key = issue.getString("key");

            //access fields area
            JSONObject fields = issue.getJSONObject("fields");

            //directly get creation and resolution date
            String resolutionDateString = fields.getString("resolutiondate");
            String creationDateString = fields.getString("created");

            //get the AV if present
            JSONArray av = fields.getJSONArray("versions");

            Date resolutionDate = formatter.parse(resolutionDateString);
            Date creationDate = formatter.parse(creationDateString);


            //here I get the resolution and creation release of the ticket
            Release creationRelease = getReleaseFromDate(releasesList,creationDate);
            Release resolutionRelease = getReleaseFromDate(releasesList,resolutionDate);
            if(creationRelease == null || resolutionRelease == null) continue;
            Date firstDate = null;

            if(av.length() > 0){
                firstDate = validateAV(resolutionRelease,creationRelease,av);
            }


            Ticket ticket;
            //if it has a valid AV, save it
            if(firstDate != null && creationRelease.getDate().before(resolutionRelease.getDate())){

                Release corrRelease = getReleaseFromDate(releasesList,firstDate);
                ticket = new Ticket(key,creationRelease,resolutionRelease,corrRelease);
                this.ticketsWithValidAV.add(ticket);

            }
            else{
                ticket = new Ticket(key,creationRelease,resolutionRelease,null);
            }

            allTickets.add(ticket);

        }

        return allTickets;
    }

    //This method verifies that the creation release and the affected versions are not inconsistent,
    //i.e. checks if IV > OV or if IV = OV
    private Date validateAV(Release resolution, Release creation, JSONArray av) throws ParseException {

        int avLen = av.length();
        Date firstDate = null;
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");

        for (int i = 0; i < avLen; i++){
            JSONObject avElem = av.getJSONObject(i);
            if (avElem.getBoolean("released")){
                String releaseDateString = null;
                try {
                    releaseDateString = avElem.getString("releaseDate");
                }
                catch(JSONException e){
                    continue ;
                }
                if (releaseDateString != null) {
                    Date releaseDate = formatter.parse(releaseDateString);
                    Date temp = firstDateGetter(releaseDate, resolution, firstDate);
                    if (temp != null) {
                        firstDate = temp;
                    }
                }
            }
        }
        // check that IV < OV
        if(firstDate != null && creation.getDate().after(firstDate)){
            return firstDate;
        }
        return null;
    }

    private Date firstDateGetter(Date releaseDate,Release resolution,Date firstDate){
        Date temp = null;
        if (releaseDate.before(resolution.getDate()) && (firstDate == null || releaseDate.before(firstDate))){
            temp = releaseDate;
        }
        return temp;
    }

    private JSONObject getInfoFromJira(int numResults, int startAt) throws IOException {
        HttpClient client = HttpClient.newHttpClient();
        String urlString = "https://issues.apache.org/jira/rest/api/2/search?jql=project=%22" + this.projKey
                + "%22AND%22issueType%22=%22Bug%22AND(%22status%22=%22closed%22OR%22status%22=%22resolved%22)"
                + "AND%22resolution%22=%22fixed%22&fields=key,resolutiondate,versions,created&startAt=" + startAt
                + "&maxResults=" + numResults;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlString))
                .GET()
                .build();

        try {
            // not applicable try with resources for hhttp request
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return new JSONObject(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }



    //This method retrieves the creation and resolution releases of the ticket
    private Release getReleaseFromDate(List<Release> list, Date date){

        int len = list.size();
        if(date.before(list.get(0).getDate()) || date.equals(list.get(0).getDate())){
            return list.get(0);
        }
        if(date.after(list.get(len-1).getDate())){
            return null;
        }


        for(int i = 0; i < len; i++){
            if(date.equals(list.get(i).getDate())){
                return list.get(i);
            }
            if(date.after(list.get(i).getDate()) && date.before(list.get(i+1).getDate())){
                return list.get(i+1);
            }
        }
        return null;
    }


    /*This method retrieves all the versions of the project (Avro or bookkeeper) that are released and with a release date*/
    public List<Release> retrieveReleases() throws JSONException, IOException, ParseException {
        try {
            List<Release> allRelease = new ArrayList<>();
            SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
            String urlString = "https://issues.apache.org/jira/rest/api/latest/project/" + projKey + "/version";

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlString))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject json = new JSONObject(response.body());
            JSONArray values = json.getJSONArray("values");

            for (int i = 0; i < values.length(); i++) {
                JSONObject value = values.getJSONObject(i);
                if (value.getBoolean("released")) {
                    String name = value.getString("name");
                    String date = value.optString("releaseDate");
                    if (date.isEmpty()) {
                        continue;
                    }
                    Release temp = new Release(-1, name, formatter.parse(date));
                    allRelease.add(temp);
                }
            }

            sortReleaseList(allRelease);
            return allRelease;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Request interrupted", e);
        }
    }


    public List<Ticket> correctTickets(List<Ticket> allTicket){
        return allTicket;
    }

    private class ReleaseComparator implements java.util.Comparator<Release> {
        @Override
        public int compare(Release a, Release b) {
            return a.getDate().compareTo(b.getDate());
        }
    }
    public void sortReleaseList(List<Release> list){

        int len = list.size();

        Collections.sort(list,new ReleaseComparator());

        for (int i = 0; i < len; i++){
            list.get(i).setId(i+1);
        }
    }
    public static  List<Ticket> getAllReleaseTicket(Release release, List<Ticket> allTicket) {
        List<Ticket> releaseTickets = new ArrayList<>();
        for (Ticket ticket: allTicket){
            if (ticket.getOv().getId() <= release.getId()
            && ticket.getFv().getId()>=ticket.getOv().getId()
            ){
                releaseTickets.add(ticket);
            }
        }
        return releaseTickets;
    }
}
