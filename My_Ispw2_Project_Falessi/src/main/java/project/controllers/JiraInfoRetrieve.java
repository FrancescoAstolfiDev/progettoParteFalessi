package project.controllers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.json.JSONTokener;
import project.models.Release;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import project.models.Ticket;

import static java.lang.System.*;


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

    //DA JIRA PRENDO LE AFFECTED VERSION, LA FIXED VERSION, LA RESOLUTION DATE, LA CREATION DATE E LA KEY
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

            //prendo i-esimo ticket
            JSONObject issue = issues.getJSONObject(i);

            //prendo la key
            String key = issue.getString("key");

            //accedo area fields
            JSONObject fields = issue.getJSONObject("fields");

            //ottengo direttamente creation e resolution date
            String resolutionDateString = fields.getString("resolutiondate");
            String creationDateString = fields.getString("created");

            //ottengo le AV se ci sono
            JSONArray av = fields.getJSONArray("versions");

            Date resolutionDate = formatter.parse(resolutionDateString);
            Date creationDate = formatter.parse(creationDateString);


            //qui ottengo la release di resolution e di creation del ticket
            Release creationRelease = getReleaseFromDate(releasesList,creationDate);
            Release resolutionRelease = getReleaseFromDate(releasesList,resolutionDate);
            if(creationRelease == null || resolutionRelease == null) continue;
            Date firstDate = null;

            if(av.length() > 0){
                firstDate = validateAV(resolutionRelease,creationRelease,av);
            }


            Ticket ticket;
            //se ha AV valido lo salvo
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

    //Questo metodo va a verificare che la release di creation e le affected version non siano inconsistenti, ovvero
    //controlla se IV > OV o se IV = OV
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
                    continue;
                }
                Date releaseDate = formatter.parse(releaseDateString);

                Date temp = firstDateGetter(releaseDate,resolution,firstDate);
                if(temp == null){
                    return null;
                }
                firstDate = temp;
            }
        }
        // controllo che IV < OV
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
        String urlString = "https://issues.apache.org/jira/rest/api/2/search?jql=project=%22"+this.projKey
                +"%22AND%22issueType%22=%22Bug%22AND(%22status%22=%22closed%22OR%22status%22=%22resolved%22)"+
                "AND%22resolution%22=%22fixed%22&fields=key,resolutiondate,versions,created&startAt="+startAt+
                "&maxResults="+numResults;

        URL url = new URL(urlString);
        InputStream inputStream = url.openStream();
        JSONTokener tokener = new JSONTokener(inputStream);
        return new JSONObject(tokener);
    }


    //Questo metodo recupera le release di creazione e risoluzione del ticket
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


    /*This method retrieves all the versions of the project (Avro or Bookkeeper) that are released and with a release date*/
    public List<Release> retrieveReleases() throws JSONException, IOException, ParseException {

        List<Release> allRelease = new ArrayList<>();

        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
        String urlString = "https://issues.apache.org/jira/rest/api/latest/project/"+projKey+"/version";
        URL url = new URL(urlString);
        InputStream inputStream = url.openStream();
        JSONTokener tokener = new JSONTokener(inputStream);
        JSONObject json = new JSONObject(tokener);

        JSONArray values = json.getJSONArray("values");

        for (int i = 0; i < values.length(); i++) {
            JSONObject value = values.getJSONObject(i);
            if(value.getBoolean("released")){
                String name = value.getString("name");
                String date = null;
                try {
                    date = value.getString("releaseDate");
                }
                catch(JSONException e){
                    continue;
                }
                Release temp = new Release(-1,name,formatter.parse(date));
                allRelease.add(temp);
            }
        }

        sortReleaseList(allRelease);

        return allRelease;
    }


    public void sortReleaseList(List<Release> list){

        int len = list.size();

        Collections.sort(list,new ReleaseComparator());

        for (int i = 0; i < len; i++){
            list.get(i).setId(i+1);
        }

    }

    //assegno ad ogni release tutti i ticket risolti entro la release stessa, ovvero le fv del ticket deve essere minore
    // o uguale alla release presa in considerazione dall'iterazione del for
    public void assignTicketToRelease(List<Release> releaseList, List<Ticket> allTicket){
        int len = releaseList.size()-1;
        for (int i = len; i >= 0; i--){
            int releaseId = releaseList.get(i).getId();
            List<Ticket> releaseTickets = new ArrayList<>();
            for (Ticket ticket: allTicket){
                if (ticket.getFv().getId() <= releaseId){
                    releaseTickets.add(ticket);
                }
            }
            releaseList.get(i).setAllReleaseTicket(releaseTickets);
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
}
