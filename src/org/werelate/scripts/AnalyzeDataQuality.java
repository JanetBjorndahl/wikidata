/*
 * Copyright 2012 Foundation for On-Line Genealogy, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.werelate.scripts;

import org.werelate.parser.StructuredDataParser;
import org.werelate.parser.WikiReader;
import org.werelate.utils.Util;
import org.werelate.utils.EventDate;

import java.util.Calendar;
import java.text.SimpleDateFormat;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.sql.*;
import java.sql.Connection;
import org.apache.log4j.Logger;
import java.util.HashMap;

import nu.xom.ParsingException;
import nu.xom.Element;
import nu.xom.Elements;

/**
 * This class analyzes pages and derives some information
 * User: DataAnalyst
 * Date: Mar 2021
 */
public class AnalyzeDataQuality extends StructuredDataParser {
   private Connection sqlCon;
   private static int jobId = 0, round = 0, rows = 0, issueRows = 0;
   private static SimpleDateFormat cachedtf = new SimpleDateFormat("yyyyMMddkkmmss");
   private static SimpleDateFormat ymdf = new SimpleDateFormat("yyyy-MM-dd");
   private static Calendar startTime = Calendar.getInstance();
   private static int thisYear = startTime.get(Calendar.YEAR);
   // Assumptions for year calculations
   private static int usualLongestLife = 110, absLongestLife = 125;
   private static int minMarriageAge = 12, maxMarriageAge = 80, maxSpouseGap = 30;
   private static int usualYoungestFather = 15, usualYoungestMother = 12;
   private static int absYoungestFather = 8, absYoungestMother = 4; 
   private static int usualOldestFather = 70, usualOldestMother = 50;
   private static int absOldestFather = 110, absOldestMother = 80;
   private static int maxSiblingGap = 30, maxAfterParentMarriage = 35;

   // For round 1
   private static String[] rowValue = new String[1000];
   // For subsequent rounds
   private static int[] pageId = new int[1000];
   private static String[] pageTitle = new String[1000];
   private static Integer[] actualBirth = new Integer[1000];
   private static Integer[] earliestBirth = new Integer[1000];
   private static Integer[] latestBirth = new Integer[1000];
   private static Integer[] latestDeath = new Integer[1000];
   private static String[] parentPage = new String[1000];
   private static short[] bornB4ParMarrInd = new short[1000];
   private static int[] latestRoundId = new int[1000];
   private static String[] msg = new String[1000];
   private static String[] birthCalc = new String[1000];
   // For all rounds
   private static int[] issuePageId = new int[1000];
   private static String[] issueDesc = new String[1000];
   private static String[] issueRowValue = new String[1000];
   
   public void parse(String title, String text, int pageId, int latestRevId, String username, String timestamp, String comment)
           throws IOException, ParsingException {

      if (!text.startsWith("#REDIRECT")) {  // Ignore redirected pages
         String[] splitTitle = title.split(":",2);
         if (splitTitle[0].equals("Person") || splitTitle[0].equals("Family")) {
            int ns = splitTitle[0].equals("Person") ? 108 : 110;
            String[] split = splitStructuredWikiText(splitTitle[0].equals("Person") ? "person" : "family", text);
            String structuredData = split[0];
            Integer actualBirth = null;
            Integer earliestBirth = null;
            Integer latestBirth = null;
            Integer latestDeath = null;
            Integer earliestMarriage = null;
            Integer latestMarriage = null;
            String parentPage = null, husbandPage = null, wifePage = null;
            String dq_gender = null;
            short famousInd = 0, ancientInd = 0, diedYoungInd = 0, bornB4ParMarrInd = 0, dateErrorInd = 0;
            String msg = "", birthCalc = "";

            if (!Util.isEmpty(structuredData)) {
               Element root = parseText(structuredData).getRootElement();
               Element elm;
               Elements elms;
               Integer actualYear = null, earliestYear = null, latestYear = null;

               // Extract information and prepare for sql
               elms = root.getChildElements("event_fact");
               for (int i = 0; i < elms.size(); i++) {
                  elm = elms.get(i);
                  String date = elm.getAttributeValue("date");
                  if (date==null) {
                     date = "";
                  }
                  actualYear = null;
                  earliestYear = null;
                  latestYear = null;

                  // Determine the date's actual, earliest and latest years based on modifiers.
                  if (!date.equals("")) {
                     try {
                        EventDate eventDate = new EventDate(date);
                        date = eventDate.formatDate();
                        if (!eventDate.editDate()) {
                           dateErrorInd = 1;
                        }

                        if (date.startsWith("Aft") || date.startsWith("Bet") || date.startsWith("From")) {
                           earliestYear = Integer.valueOf(eventDate.getEffectiveFirstYear());
                        }
                        if (date.startsWith("Bef") || date.startsWith("Bet") || date.startsWith("To") ||
                              (date.startsWith("From") && date.contains("to")) ) {
                           latestYear = Integer.valueOf(eventDate.getEffectiveYear());
                        }
                        if (!date.startsWith("Aft") && !date.startsWith("Bef") && !date.startsWith("Bet") &&
                              !date.startsWith("From") && !date.startsWith("To")) {
                           actualYear = Integer.valueOf(eventDate.getEffectiveYear());
                           earliestYear = actualYear;
                           latestYear = actualYear;
                        }
                     } catch (NumberFormatException e) {}
                  }

                  // Set indicator for early death.
                  String eventType = elm.getAttributeValue("type");
                  if (eventType.equals("Death") && (date.startsWith("(in infancy") || date.startsWith("(young"))) {
                     diedYoungInd = 1;
                  }
                  if (eventType.equals("Stillborn"))  {
                     diedYoungInd = 1;
                  }

                  // For a person, determine actual, earliest and latest birth year, and latest death year.
                  // Earliest and latest birth years can be changed in later rounds, based on dates of family members.
                  // Latest death year is based solely on the info on this Person page.
                  if (ns == 108) {
                     if (eventType.equals("Birth") || (eventType.equals("Christening") && latestBirth == null)) {
                        actualBirth = actualYear;
                        earliestBirth = earliestYear;
                        latestBirth = latestYear;
                        if (earliestBirth != null && latestBirth == null) {
                           latestBirth = earliestBirth + usualLongestLife;  // somewhat arbitrary, but needs a value if earliest is set
                        }
                     }
                     else {
                        if (!eventType.startsWith("Alt")) {    // ignore Alt events
                           if (latestBirth == null || (latestYear != null && latestYear < latestBirth)) {
                              latestBirth = latestYear;
                           }
                           /* If we haven't already set birth year, and the new date is after a date, 
                              assume the person was born within 110 years of the date, in case there are no other
                              dates with more info. */
                           if (earliestBirth == null && latestBirth == null && earliestYear != null) {
                              earliestBirth = earliestYear - usualLongestLife;
                              latestBirth = earliestYear + usualLongestLife;
                           }
                        }
                     }
                     // Ignore future and estimated death dates - these signify an unknown death date.
                     if (latestYear != null && latestYear <= thisYear && !date.contains("Est")) {
                        if (eventType.equals("Death") || (eventType.equals("Burial") && latestDeath == null)) {
                           latestDeath = latestYear;
                        }
                     }
                     // Latest birth could be after latest death if there is a post-death event such as probate.
                     if (latestBirth != null && latestDeath != null && latestBirth > latestDeath) {
                        latestBirth = latestDeath;
                     }
                  }

                  // For a family, determine earliest and latest marriage year. These years are based solely on
                  // the info on this family page.
                  else {
                     if (eventType.equals("Marriage")) {
                        earliestMarriage = earliestYear;
                        latestMarriage = latestYear;
                     }
                     if (earliestMarriage == null && (eventType.startsWith("Marriage") || eventType.equals("Engagement"))) {
                        earliestMarriage = earliestYear;
                     }
                     if (latestYear != null && (latestMarriage == null || latestYear < latestMarriage) && 
                           !eventType.equals("Engagement") && !eventType.equals("Alt Marriage")) {
                        latestMarriage = latestYear;
                     }
                  }
               }

               // If any event found, but not birth/christening, earliest birth is 110 years before the event.
               if (ns == 108) {
                  if (latestBirth != null && earliestBirth == null) {
                     earliestBirth = latestBirth - usualLongestLife;
                  }
               }
               if (ns == 108) {
                  elms = root.getChildElements("child_of_family");
                  if (elms.size() > 0) {
                     elm = elms.get(0);
                     parentPage = SqlTitle(elm.getAttributeValue("title"));
                     if (elms.size() > 1) {
                        createIssue("Anomaly","Multiple sets of parents", pageId);
                     }
                  }

                  elms = root.getChildElements("gender");
                  if (elms.size() > 0) {
                     elm = elms.get(0);
                     dq_gender = elm.getValue();
                  }
                  if (dq_gender == null || dq_gender == "") {
                     createIssue("Incomplete","Missing gender", pageId);
                  }
               }   
               else {
                  elms = root.getChildElements("husband");
                  if (elms.size() > 0) {
                     elm = elms.get(0);
                     husbandPage = SqlTitle(elm.getAttributeValue("title"));
                     if (elms.size() > 1) {
                        createIssue("Error","More than one husband on a family page", pageId);
                     }
                  }
                  elms = root.getChildElements("wife");
                  if (elms.size() > 0) {
                     elm = elms.get(0);
                     wifePage = SqlTitle(elm.getAttributeValue("title"));
                     if (elms.size() > 1) {
                        createIssue("Error","More than one wife on a family page", pageId);
                     }
                  }
               }   
            }
            if (text.contains("{{FamousLivingPersonException") || text.contains("{{Wikidata|Q")) {
               famousInd = 1;
            }
            if (text.contains("{{Wr-too-far-back}}")) {
               ancientInd = 1;
            }
            if (text.contains("{{BirthBeforeParentsMarriage")) {
               bornB4ParMarrInd = 1;
            }
            if (earliestBirth != null && earliestBirth > latestBirth) {
               createIssue("Anomaly","Event(s) before birth", pageId);
               latestBirth = earliestBirth;    // Ignore latest birth year set by event(s) before birth
            }
            rowValue[rows++] = " (" + jobId + "," + pageId + "," + ns + ",\"" + SqlTitle(splitTitle[1]) +
                      "\"," + actualBirth + "," + earliestBirth + "," +
                     latestBirth + "," + 
                     latestDeath + "," + earliestMarriage + "," + latestMarriage + "," +
                     (parentPage==null ? "null" : "\"" + parentPage + "\"") + "," + 
                     (husbandPage==null ? "null" : "\"" + husbandPage + "\"") + "," + 
                     (wifePage==null ? "null" : "\"" + wifePage + "\"") + "," + 
                     diedYoungInd + "," +
                     famousInd + "," + ancientInd + ",\"" + 
                     username + "\",\"" + birthCalc + "\"" + ")";
            if (rows==500) {
               insertRows();
            }
         }
      }
   }

      /* Prepare the issue for writing to the database (and write if array is full). */
   private void createIssue(String cat, String desc, int pageId) {
      // Check for a duplicate issue - if so, ignore
      for (int j=0; j<issueRows; j++) {
         if (issuePageId[j]==pageId && issueDesc[j].equals(desc)) {
            return;
         }
      } 
      issuePageId[issueRows] = pageId;
      issueDesc[issueRows] = desc;
      issueRowValue[issueRows++] = " (" + jobId + "," + pageId + ",\"" + cat + "\",\"" + desc +
            "\"," + "\"\", \"\")";
      if (issueRows==1000) {
         insertIssueRows();
      }
   }

   /* Prepare the issue for writing to the database (and write if array is full. */
   private void createIssue(int i, String cat, String desc) {

      // Check for a duplicate issue - if so, ignore
      for (int j=0; j<issueRows; j++) {
         if (issuePageId[j]==pageId[i] && issueDesc[j].equals(desc)) {
            return;
         }
      } 
      issuePageId[issueRows] = pageId[i];
      issueDesc[issueRows] = desc;
      issueRowValue[issueRows++] = " (" + jobId + "," + pageId[i] + ",\"" + cat + "\",\"" + desc +
            "\"," + "\"\", \"\")";
      if (issueRows==1000) {
         insertIssueRows();
      }
   }

   private void insertRows() {
      String sql = "INSERT INTO dq_page_analysis (dq_job_id, dq_page_id, dq_namespace, dq_title, " +
                   "dq_actual_birth_year, dq_earliest_birth_year, dq_latest_birth_year, " +
                   "dq_latest_death_year, dq_earliest_marriage_year, dq_latest_marriage_year, " +
                   "dq_parent_page, dq_husband_page, dq_wife_page, " +
                   "dq_died_young_ind, dq_famous_ind, dq_ancient_ind, " +
                   "dq_last_user, dq_birth_calc) VALUES";
      for (int i=0; i<rows; i++) {
         sql += rowValue[i];
         if (i<(rows-1)) {
            sql += ", ";
         }
         else {
            sql += ";";
         }
      }
      try (PreparedStatement stmt = sqlCon.prepareStatement(sql)) {
         stmt.executeUpdate();
         commitSql();
      } catch (SQLException e) {
         rollbackSql();
         e.printStackTrace();
      }
      rows = 0; 
   }

   private void insertIssueRows() {
      String sql = "INSERT INTO dq_issue (dqi_job_id, dqi_page_id, dqi_category, dqi_issue_desc, " +
                   "dqi_verified_by, dqi_viewed_by) VALUES";
      if (issueRows>0) {
         for (int i=0; i<issueRows; i++) {
            sql += issueRowValue[i];
            if (i<(issueRows-1)) {
               sql += ", ";
            }
            else {
               sql += ";";
            }
         }
         try (PreparedStatement stmt = sqlCon.prepareStatement(sql)) {
            stmt.executeUpdate();
            commitSql();
         } catch (SQLException e) {
            rollbackSql();
            e.printStackTrace();
         } 
      issueRows = 0;
      }
   }

   private void nextRound() {
      int limit = 500;
      int processedRows = 0;
      int updatedRows = 0;
      String query;

      System.out.print("Processing round " + round);
      /* In round 2, process all rows to find inter-generational errors. 
         In subsequent rounds, process rows without a birth year or where there is a significant gap 
         between earliest and latest birth year. */
      if (round == 2) {
         query = "SELECT dq_page_id, dq_title, dq_actual_birth_year, dq_earliest_birth_year," +
                 " dq_latest_birth_year, dq_latest_death_year, dq_parent_page, dq_birth_calc" + 
                 " FROM dq_page_analysis" +
                 " WHERE dq_job_id = " + jobId + " AND dq_namespace = 108 AND dq_page_id > ?" +
                 " ORDER BY dq_page_id LIMIT " + limit + ";";
      }
      else {                   
         query = "SELECT dq_page_id, dq_title, dq_actual_birth_year, dq_earliest_birth_year," +
                 " dq_latest_birth_year, dq_latest_death_year, dq_parent_page, dq_birth_calc" + 
                 " FROM dq_page_analysis" +
                 " WHERE dq_job_id = " + jobId + " AND dq_namespace = 108 AND dq_page_id > ?" +
                 " AND (dq_latest_birth_year is null OR (dq_latest_birth_year - dq_earliest_birth_year) > 10)" +
                 " ORDER BY dq_page_id LIMIT " + limit + ";";
      }
      
      try (PreparedStatement stmt = sqlCon.prepareStatement(query)) {
         int startPageId = 0, rows = 0;

         // Read rows with inadequate data, a batch of records at a time, and save in a set of arrays
         do {
            stmt.setInt(1,startPageId);
            try (ResultSet rs = stmt.executeQuery()) {
               rows=0;
               while (rs.next()) {
                  pageId[rows] = rs.getInt("dq_page_id");
                  pageTitle[rows] = SqlTitle(rs.getString("dq_title"));
                  actualBirth[rows] = getInteger(rs, "dq_actual_birth_year");
                  earliestBirth[rows] = getInteger(rs, "dq_earliest_birth_year");
                  latestBirth[rows] = getInteger(rs, "dq_latest_birth_year");
                  latestDeath[rows] = getInteger(rs, "dq_latest_death_year");
                  parentPage[rows] = SqlTitle(rs.getString("dq_parent_page"));
                  birthCalc[rows] = rs.getString("dq_birth_calc");
                  latestRoundId[rows] = 0;  // reset for this group of records
                  rows++;
               }
               processedRows += rows;

               // Derive dates and update the database for this batch of records.
               if (rows > 0) {            // This check only required for small test files
                  deriveDates(rows);
                  updatedRows += updateRows(rows);
                  insertIssueRows();      
               }

               if (processedRows % 10000 == 0) {
                  System.out.print(".");
               }
               
               // Get startPageId for next batch
               if (rows>0) {
                  startPageId = pageId[rows-1];
               }
            } catch (SQLException e) {
               e.printStackTrace();
            }
         }
         while (rows==limit); // Loop only if last result set was full

      System.out.println();

      } catch (SQLException e) {
         e.printStackTrace();
      }
      logger.info("Job #" + jobId + " round " + round + " processed " + 
            processedRows + " rows and updated " + updatedRows + " rows");
   }

   // Derive dates for the current batch of Persons based on dates of children, spouses and parents.
   private void deriveDates(int size) {
      // Create selection criteria for children/spouses and parents for the entire batch.
      String parSel = "", childSel = "";
      for (int i=0; i<size; i++) {
         childSel += (childSel.equals("") ? "" : ", ");
         childSel += "\"" + pageTitle[i] + "\"";
         if (parentPage[i] != null) {
            parSel += (parSel.equals("") ? "" : ", ");
            parSel += "\"" + parentPage[i] + "\"";
         }
      }

      // Get info for children (start with families where a person in the batch is the husband or wife)
      String childQuery =  "SELECT \"father\" as parent," + 
                           " f.dq_husband_page AS dq_title," +
                           " c.dq_title AS child_dq_title," +
                           " c.dq_earliest_birth_year AS child_dq_earliest_birth_year," +
                           " c.dq_latest_birth_year AS child_dq_latest_birth_year" +
                           " FROM dq_page_analysis f USE INDEX (dq_husband_page) " +
                           " INNER JOIN dq_page_analysis c USE INDEX (dq_parent_page) ON f.dq_title = c.dq_parent_page" +
                           " WHERE f.dq_job_id = " + jobId + " AND f.dq_namespace = 110 " +
                           " AND c.dq_job_id = " + jobId + " AND c.dq_namespace = 108 " +
                           " AND f.dq_husband_page IN ( " + childSel + " ) " +
                           " UNION " +
                           " SELECT \"mother\" as parent," +  
                           " f.dq_wife_page AS dq_title," +
                           " c.dq_title AS child_dq_title," +
                           " c.dq_earliest_birth_year AS child_dq_earliest_birth_year," +
                           " c.dq_latest_birth_year AS child_dq_latest_birth_year" +
                           " FROM dq_page_analysis f USE INDEX (dq_wife_page)" +
                           " INNER JOIN dq_page_analysis c USE INDEX (dq_parent_page) ON f.dq_title = c.dq_parent_page" +
                           " WHERE f.dq_job_id = " + jobId + " AND f.dq_namespace = 110 " +
                           " AND c.dq_job_id = " + jobId + " AND c.dq_namespace = 108 " +
                           " AND f.dq_wife_page IN ( " + childSel + " );";
      try (PreparedStatement stmt = sqlCon.prepareStatement(childQuery)) {
         try (ResultSet rs = stmt.executeQuery()) {
            while(rs.next()) {
               String selfPageTitle = SqlTitle(rs.getString("dq_title"));
               String cPageTitle = SqlTitle(rs.getString("child_dq_title"));
               String parent = rs.getString("parent");
               Integer cEarliestBirth = getInteger(rs, "child_dq_earliest_birth_year");
               Integer cLatestBirth = getInteger(rs, "child_dq_latest_birth_year");

               for (int i=0; i<size; i++) {
                  if (selfPageTitle.equals(pageTitle[i])) {
                     /* Update calc of earliest and latest birth years */
                     if (parent.equals("mother") && cEarliestBirth!=null && 
                           (earliestBirth[i]==null || (cEarliestBirth - usualOldestMother) > earliestBirth[i])) {
                        earliestBirth[i] = cEarliestBirth - usualOldestMother;
                        setBirthCalc(i, "child", cPageTitle);
                     }
                     if (parent.equals("father") && cEarliestBirth!=null && 
                           (earliestBirth[i]==null || (cEarliestBirth - usualOldestFather) > earliestBirth[i])) {
                        earliestBirth[i] = cEarliestBirth - usualOldestFather;
                        setBirthCalc(i,"child", cPageTitle);
                     }
                     if (parent.equals("mother") && cLatestBirth!=null && 
                           (latestBirth[i]==null || (cLatestBirth - usualYoungestMother) < latestBirth[i])) {
                        latestBirth[i] = cLatestBirth - usualYoungestMother;
                        setBirthCalc(i,"child", cPageTitle);
                     }
                     if (parent.equals("father") && cLatestBirth!=null && 
                           (latestBirth[i]==null || (cLatestBirth - usualYoungestFather) < latestBirth[i])) {
                        latestBirth[i] = cLatestBirth - usualYoungestFather;
                        setBirthCalc(i,"child", cPageTitle);
                     }
                  }
               }
            }   
         } catch (SQLException e) {
            e.printStackTrace();
         }
      } catch (SQLException e) {
         e.printStackTrace();
      }

      // Get own marriage date and info for spouses
      String spouseQuery = "SELECT f.dq_page_id AS family_page_id," +
                           " \"Husband\" AS role," +
                           " f.dq_husband_page AS dq_title," +
                           " s.dq_title AS spouse_dq_title," +
                           " f.dq_earliest_marriage_year AS dq_earliest_marriage_year," +
                           " f.dq_latest_marriage_year AS dq_latest_marriage_year," +
                           " s.dq_earliest_birth_year AS spouse_dq_earliest_birth_year," +
                           " s.dq_latest_birth_year AS spouse_dq_latest_birth_year" +
                           " FROM dq_page_analysis f USE INDEX (dq_husband_page)" +
                           " LEFT OUTER JOIN dq_page_analysis s USE INDEX (dq_title) ON f.dq_wife_page = s.dq_title" +
                           " AND s.dq_job_id = " + jobId + " AND s.dq_namespace = 108 " +
                           " WHERE f.dq_job_id = " + jobId + " AND f.dq_namespace = 110 " +
                           " AND f.dq_husband_page IN ( " + childSel + " ) " +
                           " UNION " +
                           " SELECT f.dq_page_id AS family_page_id," + 
                           " \"Wife\" AS role," +
                           " f.dq_wife_page AS dq_title," +
                           " s.dq_title AS spouse_dq_title," +
                           " f.dq_earliest_marriage_year AS dq_earliest_marriage_year," +
                           " f.dq_latest_marriage_year AS dq_latest_marriage_year," +
                           " s.dq_earliest_birth_year AS spouse_dq_earliest_birth_year," +
                           " s.dq_latest_birth_year AS spouse_dq_latest_birth_year" +
                           " FROM dq_page_analysis f USE INDEX (dq_wife_page)" +
                           " LEFT OUTER JOIN dq_page_analysis s USE INDEX (dq_title) ON f.dq_husband_page = s.dq_title" +
                           " AND s.dq_job_id = " + jobId + " AND s.dq_namespace = 108 " +
                           " WHERE f.dq_job_id = " + jobId + " AND f.dq_namespace = 110 " +
                           " AND f.dq_wife_page IN ( " + childSel + " );";
      
      try (PreparedStatement stmt = sqlCon.prepareStatement(spouseQuery)) {
         try (ResultSet rs = stmt.executeQuery()) {
            while(rs.next()) {
               int familyPageId = rs.getInt("family_page_id");
               String selfRole = SqlTitle(rs.getString("role"));
               String selfPageTitle = SqlTitle(rs.getString("dq_title"));
               String spousePageTitle = SqlTitle(rs.getString("spouse_dq_title"));
               Integer earliestMarriageYear = getInteger(rs, "dq_earliest_marriage_year");
               Integer latestMarriageYear = getInteger(rs, "dq_latest_marriage_year");
               Integer sEarliestBirth = getInteger(rs, "spouse_dq_earliest_birth_year");
               Integer sLatestBirth = getInteger(rs, "spouse_dq_latest_birth_year");
               for (int i=0; i<size; i++) {
                  if (selfPageTitle.equals(pageTitle[i])) {
                     /* Check for errors (only in second round) */
                     if (round==2) {
                        if (latestMarriageYear!=null) {
                           if (actualBirth[i]!=null && latestMarriageYear < actualBirth[i] + minMarriageAge) {
                              createIssue("Anomaly",selfRole + " younger than " + minMarriageAge + " at marriage", familyPageId);
                           }
                        }
                        if (earliestMarriageYear!=null) {
                           if (actualBirth[i]!=null && earliestMarriageYear > actualBirth[i] + absLongestLife) {
                              createIssue("Error",selfRole + " older than " + absLongestLife + " at marriage", familyPageId);
                           }
                           else {
                              if (actualBirth[i]!=null && earliestMarriageYear > actualBirth[i] + maxMarriageAge) {
                                 createIssue("Anomaly",selfRole + " older than " + maxMarriageAge + " at marriage", familyPageId);
                              }
                           }
                           if (latestDeath[i]!=null && earliestMarriageYear > latestDeath[i]) {
                              createIssue("Error","Married after death of " + selfRole.toLowerCase(), familyPageId);
                           }
                        }
                     }

                     /* Update calc of earliest and latest birth years */
                     if (earliestMarriageYear!=null && 
                           (earliestBirth[i]==null || (earliestMarriageYear - maxMarriageAge) > earliestBirth[i])) {
                        earliestBirth[i] = earliestMarriageYear - maxMarriageAge;
                        if (latestBirth[i]==null) {
                           latestBirth[i] = earliestBirth[i] + usualLongestLife; // somewhat arbitrary but needs a value if earliest set
                        }
                        setBirthCalc(i,"own marriage", "");
                     }
                     if (latestMarriageYear!=null && (latestBirth[i]==null || 
                           (latestMarriageYear - minMarriageAge) < latestBirth[i])) {
                        latestBirth[i] = latestMarriageYear - minMarriageAge;
                        if (earliestBirth[i]==null) {
                           earliestBirth[i] = latestBirth[i] - usualLongestLife;  // somewhat arbitrary but needs a value if latest set
                        }
                        setBirthCalc(i,"own marriage", "");
                     }
                     if (sEarliestBirth!=null && (earliestBirth[i]==null || 
                           (sEarliestBirth - maxSpouseGap) > earliestBirth[i])) {
                        earliestBirth[i] = sEarliestBirth - maxSpouseGap;
                        setBirthCalc(i,"spouse", spousePageTitle);
                     }
                     if (sLatestBirth!=null && (latestBirth[i]==null || 
                           (sLatestBirth + maxSpouseGap) < latestBirth[i])) {
                        latestBirth[i] = sLatestBirth + maxSpouseGap;
                        setBirthCalc(i,"spouse", spousePageTitle);
                     }
                  }
               }
            }   
         } catch (SQLException e) {
            e.printStackTrace();
         }
      } catch (SQLException e) {
         e.printStackTrace();
      }

      // Get info for parents
      if (!parSel.equals("")) {
         String parQuery = "SELECT f.dq_title AS dq_parent_page," +
                           " f.dq_earliest_marriage_year AS dq_earliest_marriage_year," +
                           " f.dq_latest_marriage_year AS dq_latest_marriage_year," +
                           " f.dq_husband_page AS father_page," +
                           " f.dq_wife_page AS mother_page," +
                           " h.dq_actual_birth_year AS father_dq_actual_birth_year," +
                           " h.dq_earliest_birth_year AS father_dq_earliest_birth_year," +
                           " h.dq_latest_birth_year AS father_dq_latest_birth_year," +
                           " h.dq_latest_death_year AS father_dq_latest_death_year," +
                           " w.dq_actual_birth_year AS mother_dq_actual_birth_year," +
                           " w.dq_earliest_birth_year AS mother_dq_earliest_birth_year," +
                           " w.dq_latest_birth_year AS mother_dq_latest_birth_year," +
                           " w.dq_latest_death_year AS mother_dq_latest_death_year" +
                           " FROM dq_page_analysis f USE INDEX (dq_title)" +
                           " LEFT OUTER JOIN dq_page_analysis h USE INDEX (dq_title)" + 
                           " ON f.dq_husband_page = h.dq_title AND h.dq_job_id = " + jobId + " AND h.dq_namespace = 108" +
                           " LEFT OUTER JOIN dq_page_analysis w USE INDEX (dq_title)" +
                           " ON f.dq_wife_page = w.dq_title AND w.dq_job_id = " + jobId + " AND w.dq_namespace = 108" +
                           " WHERE f.dq_job_id = " + jobId + " AND f.dq_title IN ( " + parSel + 
                           " ) AND f.dq_namespace = 110;";
         try (PreparedStatement stmt = sqlCon.prepareStatement(parQuery)) {
            try (ResultSet rs = stmt.executeQuery()) {
               while(rs.next()) {
                  String parPageTitle = SqlTitle(rs.getString("dq_parent_page"));
                  String fPageTitle = SqlTitle(rs.getString("father_page"));
                  String mPageTitle = SqlTitle(rs.getString("mother_page"));
                  Integer parEarliestMarriageYear = getInteger(rs, "dq_earliest_marriage_year");
                  Integer parLatestMarriageYear = getInteger(rs, "dq_latest_marriage_year");
                  Integer fActualBirthYear = getInteger(rs, "father_dq_actual_birth_year");
                  Integer fEarliestBirthYear = getInteger(rs, "father_dq_earliest_birth_year");
                  Integer fLatestBirthYear = getInteger(rs, "father_dq_latest_birth_year");
                  Integer fLatestDeathYear = getInteger(rs, "father_dq_latest_death_year");
                  Integer mActualBirthYear = getInteger(rs, "mother_dq_actual_birth_year");
                  Integer mEarliestBirthYear = getInteger(rs, "mother_dq_earliest_birth_year");
                  Integer mLatestBirthYear = getInteger(rs, "mother_dq_latest_birth_year");
                  Integer mLatestDeathYear = getInteger(rs, "mother_dq_latest_death_year");

                  for (int i=0; i<size; i++) {
                     if (parPageTitle.equals(parentPage[i])) {
                        /* Check for errors (only in second round) */
                        if (round==2) {
                           if (actualBirth[i]!=null) {
                              if (parEarliestMarriageYear!=null && actualBirth[i] < parEarliestMarriageYear
                                    && bornB4ParMarrInd[i]==0) {     // suppress this warning if acknowledged
                                 createIssue(i,"Anomaly","Born before parents' marriage");
                              }
                              if (mActualBirthYear==null && fActualBirthYear==null
                                    && parLatestMarriageYear!=null 
                                    && actualBirth[i] > parLatestMarriageYear + maxAfterParentMarriage) {
                                 createIssue(i,"Anomaly","Born over " + 
                                       maxAfterParentMarriage + " after parents' marriage");
                              }
                              if (mActualBirthYear!=null) {
                                 if (actualBirth[i] < mActualBirthYear + absYoungestMother) {
                                    createIssue(i,"Error","Born before mother was " + absYoungestMother);
                                 }
                                 else {
                                    if (actualBirth[i] < mActualBirthYear + usualYoungestMother) {
                                       createIssue(i,"Anomaly","Born before mother was " + usualYoungestMother);
                                    }
                                 }
                                 if (actualBirth[i] > mActualBirthYear + absOldestMother) {
                                    createIssue(i,"Error","Born after mother was " + absOldestMother);
                                 }
                                 else {
                                    if (actualBirth[i] > mActualBirthYear + usualOldestMother) {
                                       createIssue(i,"Anomaly","Born after mother was " + usualOldestMother);
                                    }
                                 }
                              }
                              if (fActualBirthYear!=null) {
                                 if (actualBirth[i] < fActualBirthYear + absYoungestFather) {
                                    createIssue(i,"Error","Born before father was " + absYoungestFather);
                                 }
                                 else {
                                    if (actualBirth[i] < fActualBirthYear + usualYoungestFather) {
                                       createIssue(i,"Anomaly","Born before father was " + usualYoungestFather);
                                    }
                                 }
                                 if (actualBirth[i] > fActualBirthYear + absOldestFather) {
                                    createIssue(i,"Error","Born after father was " + absOldestFather);
                                 }
                                 else {
                                    if (actualBirth[i] > fActualBirthYear + usualOldestFather) {
                                       createIssue(i,"Anomaly","Born after father was " + usualOldestFather);
                                    }
                                 }
                              }
                              if (mLatestDeathYear!=null && actualBirth[i] > mLatestDeathYear) {
                                 createIssue(i,"Error","Born after mother died");
                              }
                              if (fLatestDeathYear!=null && actualBirth[i] > fLatestDeathYear + 1) {
                                 createIssue(i,"Error","Born more than 1 year after father died");
                              }
                           }
                        }
                     
                        /* Update calc of earliest and latest birth years */
                        if (parEarliestMarriageYear!=null && 
                              (earliestBirth[i]==null || parEarliestMarriageYear > earliestBirth[i])) {
                           // Adjust earliest birth year only if there is no actual birth year
                           if (actualBirth[i]==null) {      
                              earliestBirth[i] = parEarliestMarriageYear;
                              if (latestBirth[i]==null) {
                                 latestBirth[i] = earliestBirth[i] + usualLongestLife;  // somewhat arbitrary but needs a value if earliest set
                              }
                              setBirthCalc(i,"parent's marriage","");
                           }
                        }
                        if (parLatestMarriageYear!=null && 
                              (latestBirth[i]==null || (parLatestMarriageYear + maxAfterParentMarriage) < latestBirth[i])) {
                           latestBirth[i] = parLatestMarriageYear + maxAfterParentMarriage;
                           if (earliestBirth[i]==null) {
                              earliestBirth[i] = latestBirth[i] - usualLongestLife;  // somewhat arbitrary but needs a value if latest set
                           }
                           setBirthCalc(i,"parent's marriage","");
                        }
                        if (mEarliestBirthYear!=null && (earliestBirth[i]==null ||
                              (mEarliestBirthYear + usualYoungestMother) > earliestBirth[i])) {
                           earliestBirth[i] = mEarliestBirthYear + usualYoungestMother;
                           setBirthCalc(i,"mother's birth", mPageTitle);
                        }
                        if (mLatestBirthYear!=null && (latestBirth[i]==null || 
                              (mLatestBirthYear + usualOldestMother) < latestBirth[i])) {
                           latestBirth[i] = mLatestBirthYear + usualOldestMother;
                           setBirthCalc(i,"mother's birth", mPageTitle);
                        }
                        if (fEarliestBirthYear!=null && (earliestBirth[i]==null || 
                              (fEarliestBirthYear + usualYoungestFather) > earliestBirth[i])) {
                           earliestBirth[i] = fEarliestBirthYear + usualYoungestFather;
                           setBirthCalc(i,"father's birth", fPageTitle);
                        }
                        if (fLatestBirthYear!=null && (latestBirth[i]==null || 
                              (fLatestBirthYear + usualOldestFather) < latestBirth[i])) {
                           latestBirth[i] = fLatestBirthYear + usualOldestFather;
                           setBirthCalc(i,"father's birth", fPageTitle);
                        }
                        if (mLatestDeathYear!=null && (latestBirth[i]==null || mLatestDeathYear < latestBirth[i])) {
                           latestBirth[i] = mLatestDeathYear;
                           setBirthCalc(i,"mother's death","");
                        }
                        if (fLatestDeathYear!=null && (latestBirth[i]==null || (fLatestDeathYear + 1) < latestBirth[i])) {
                           latestBirth[i] = fLatestDeathYear + 1;
                           setBirthCalc(i,"father's death","");
                        }
                     }
                  }
               }
            } catch (SQLException e) {
               e.printStackTrace();
            }
         } catch (SQLException e) {
            e.printStackTrace();
         }
      }

      // Get info for siblings (in case there isn't a page for either mother or father)
      if (!parSel.equals("")) {
         String sibQuery = "SELECT s.dq_parent_page AS sibling_dq_parent_page," +
                           " s.dq_title AS sibling_dq_title," +
                           " s.dq_earliest_birth_year AS sibling_dq_earliest_birth_year," +
                           " s.dq_latest_birth_year AS sibling_dq_latest_birth_year" +
                           " FROM dq_page_analysis s USE INDEX (dq_parent_page)" +
                           " WHERE s.dq_job_id = " + jobId + " AND s.dq_parent_page IN ( " + parSel + 
                           " ) AND s.dq_namespace = 108;";
         try (PreparedStatement stmt = sqlCon.prepareStatement(sibQuery)) {
            try (ResultSet rs = stmt.executeQuery()) {
               while(rs.next()) {
                  String parPageTitle = SqlTitle(rs.getString("sibling_dq_parent_page"));
                  String sibPageTitle = SqlTitle(rs.getString("sibling_dq_title"));
                  Integer sEarliestBirthYear = getInteger(rs, "sibling_dq_earliest_birth_year");
                  Integer sLatestBirthYear = getInteger(rs, "sibling_dq_latest_birth_year");

                  for (int i=0; i<size; i++) {
                     if (parPageTitle.equals(parentPage[i])) {
                        if (sEarliestBirthYear != null && 
                                 (earliestBirth[i]==null || (sEarliestBirthYear - maxSiblingGap) > earliestBirth[i])) {
                           earliestBirth[i] = sEarliestBirthYear - maxSiblingGap;
                           setBirthCalc(i,"sibling",sibPageTitle);
                        }
                        if (sLatestBirthYear != null && 
                                 (latestBirth[i]==null || (sLatestBirthYear + maxSiblingGap) < latestBirth[i])) {
                           latestBirth[i] = sLatestBirthYear + maxSiblingGap;
                           setBirthCalc(i,"sibling",sibPageTitle);
                        }
                     }
                  }
               }
            } catch (SQLException e) {
               e.printStackTrace();
            }
         } catch (SQLException e) {
            e.printStackTrace();
         }
      }
   }

   /* Add the calculation to the tracking string. */
   private void setBirthCalc(int i, String check, String pageTitle) {
      birthCalc[i] += (birthCalc[i].equals("") ? "" : "; ") + check;
      birthCalc[i] += pageTitle.equals("") ? " " : (": <" + pageTitle + "> ");
      birthCalc[i] += earliestBirth[i] + "," + latestBirth[i];
      latestRoundId[i] = round;
   }
   
   private int updateRows(int size) {
      int updatedRows = 0;

      String sql = "UPDATE dq_page_analysis " +
                   "SET dq_earliest_birth_year = ? " +
                   ", dq_latest_birth_year = ? " +
                   ", dq_birth_calc = ? " +
                   "WHERE dq_job_id = " + jobId + 
                   " AND dq_page_id = ? ;";
      try (PreparedStatement stmt = sqlCon.prepareStatement(sql)) {
         for (int i=0; i<size; i++) {
            if (latestRoundId[i] == round) {
               stmt.setInt(1,earliestBirth[i]);
               stmt.setInt(2,latestBirth[i]);
               stmt.setString(3,birthCalc[i]);
               stmt.setInt(4,pageId[i]);
               stmt.executeUpdate();
               updatedRows++;
            }
         }
         commitSql();
      } catch (SQLException e) {
         rollbackSql();
         e.printStackTrace();
      } 
      return updatedRows;
   }

   private void copyIssues() {
      ResultSet rs;
      Calendar yesterday = Calendar.getInstance();
      yesterday.add(Calendar.DAY_OF_MONTH, -1);
      String effDate = ymdf.format(yesterday.getTime());
      Integer livingTh = thisYear - usualLongestLife;

      // First delete rows for all but last 2 runs
      purgeIssues();

      /* Statement to copy pages with issues to dq_page table */
      String copy = "INSERT INTO dq_page SELECT * FROM dq_page_analysis " +
                    "WHERE dq_job_id = " + jobId + 
                     " AND (dq_namespace = 108 " +
                         "AND (dq_earliest_birth_year > dq_latest_birth_year " +
                            "OR ((dq_latest_birth_year IS NULL OR dq_latest_birth_year > " + livingTh + ") " + 
                               "AND dq_latest_death_year IS NULL)) " +
                        "OR dq_page_id IN " +
                           "(SELECT dqi_page_id FROM dq_issue WHERE dqi_job_id = " + jobId + ")) " +
                    "ORDER BY dq_page_id;";  

      /* Statistics queries */                           
      String total = "SELECT COUNT(*) AS count FROM dq_page_analysis " +
                     "WHERE dq_job_id = " + jobId + " AND dq_namespace = 108;";
      String living = "SELECT COUNT(*) AS count FROM dq_page " +
                      "WHERE dq_job_id = " + jobId + " AND dq_namespace = 108 " +
                      "AND dq_latest_birth_year > " + livingTh + " " + 
                      "AND dq_latest_death_year IS NULL AND dq_famous_ind = 0 AND dq_died_young_ind = 0;";
      String probLiving = "SELECT COUNT(*) AS count FROM dq_page " +
                      "WHERE dq_job_id = " + jobId + " AND dq_namespace = 108 " +
                      "AND dq_earliest_birth_year > " + livingTh + 
                      " AND dq_latest_death_year IS NULL AND dq_famous_ind = 0 AND dq_died_young_ind = 0;";
      String noDate = "SELECT COUNT(*) AS count FROM dq_page " +
                      "WHERE dq_job_id = " + jobId + " AND dq_namespace = 108 " +
                      "AND dq_latest_birth_year IS NULL AND dq_latest_death_year IS NULL " + 
                      "AND dq_famous_ind = 0 AND dq_died_young_ind = 0;";
      String chron = "SELECT COUNT(*) AS count FROM dq_page " +
                     "WHERE dq_job_id = " + jobId + " AND dq_namespace = 108 " +
                     "AND dq_latest_birth_year < dq_earliest_birth_year;";                      
      String anomaly = "SELECT dqi_category, dqi_issue_desc, COUNT(*) AS count FROM dq_issue " +
                     "WHERE dqi_job_id = " + jobId + 
                     " GROUP BY dqi_category, dqi_issue_desc ORDER BY dqi_category, dqi_issue_desc;";

      /* Statement to write statistics to the stats table */               
      String stats = "INSERT INTO dq_stats (dqs_job_id, dqs_date, dqs_category, dqs_issue_desc, dqs_count) " +
                     "VALUES(" + jobId + ", ? , ? , ? , ? );"; 

      try (Statement stmt = sqlCon.createStatement()) {
         // Copy pages with issues to dq_page
         stmt.executeUpdate(copy);
         commitSql();

         // Output counts of pages and issues
         rs = stmt.executeQuery(total);
         if (rs.next()) {
            try (PreparedStatement insertStmt = sqlCon.prepareStatement(stats)) {
               insertStmt.setString(1,effDate);
               insertStmt.setString(2,"Growth");
               insertStmt.setString(3,"Person pages");
               insertStmt.setInt(4,rs.getInt("count"));
               insertStmt.executeUpdate();
               commitSql();
            } catch (SQLException e) {
               rollbackSql();
               e.printStackTrace();
            }
         }
         rs.close();

         rs = stmt.executeQuery(living);
         if (rs.next()) {
            try (PreparedStatement insertStmt = sqlCon.prepareStatement(stats)) {
               insertStmt.setString(1,effDate);
               insertStmt.setString(2,"Living");
               insertStmt.setString(3,"Potentially living");
               insertStmt.setInt(4,rs.getInt("count"));
               insertStmt.executeUpdate();
               commitSql();
            } catch (SQLException e) {
               rollbackSql();
               e.printStackTrace();
            }
         }
         rs.close();

         rs = stmt.executeQuery(probLiving);
         if (rs.next()) {
            try (PreparedStatement insertStmt = sqlCon.prepareStatement(stats)) {
               insertStmt.setString(1,effDate);
               insertStmt.setString(2,"Living");
               insertStmt.setString(3,"Considered living");
               insertStmt.setInt(4,rs.getInt("count"));
               insertStmt.executeUpdate();
               commitSql();
            } catch (SQLException e) {
               rollbackSql();
               e.printStackTrace();
            }
         }
         rs.close();

         rs = stmt.executeQuery(noDate);
         if (rs.next()) {
            try (PreparedStatement insertStmt = sqlCon.prepareStatement(stats)) {
               insertStmt.setString(1,effDate);
               insertStmt.setString(2,"Incomplete");
               insertStmt.setString(3,"No dates within a few generations");
               insertStmt.setInt(4,rs.getInt("count"));
               insertStmt.executeUpdate();
               commitSql();
            } catch (SQLException e) {
               rollbackSql();
               e.printStackTrace();
            }
         }
         rs.close();

         rs = stmt.executeQuery(chron);
         if (rs.next()) {
            try (PreparedStatement insertStmt = sqlCon.prepareStatement(stats)) {
               insertStmt.setString(1,effDate);
               insertStmt.setString(2,"Relationship");
               insertStmt.setString(3,"Inter-generational chronological issue");
               insertStmt.setInt(4,rs.getInt("count"));
               insertStmt.executeUpdate();
               commitSql();
            } catch (SQLException e) {
               rollbackSql();
               e.printStackTrace();
            }
         }
         rs.close();

         rs = stmt.executeQuery(anomaly);
         while (rs.next()) {
            try (PreparedStatement insertStmt = sqlCon.prepareStatement(stats)) {
               insertStmt.setString(1,effDate);
               insertStmt.setString(2,rs.getString("dqi_category"));
               insertStmt.setString(3,rs.getString("dqi_issue_desc"));
               insertStmt.setInt(4,rs.getInt("count"));
               insertStmt.executeUpdate();
               commitSql();
            } catch (SQLException e) {
               rollbackSql();
               e.printStackTrace();
            }
         }
         rs.close();
      } catch (SQLException e) {
         e.printStackTrace();
      }
   }

   private void purgeAnalysis() {
      String purge = "TRUNCATE dq_page_analysis;";
      try (Statement truncate = sqlCon.createStatement()) {
         truncate.executeUpdate(purge);
      } catch (SQLException e) {
         e.printStackTrace();
      }
   }

   private void purgeIssues() {
      int[] oldJobId = new int[20];
      String check = "SELECT DISTINCT dq_job_id FROM dq_page ORDER BY dq_job_id;";
      String purge = "DELETE FROM dq_page WHERE dq_job_id = ? ;";
      String purgeIssues = "DELETE FROM dq_issue WHERE dqi_job_id = ? ;";
      
      System.out.print("Purging issues");
      try (PreparedStatement stmt = sqlCon.prepareStatement(check);
           PreparedStatement delete = sqlCon.prepareStatement(purge);
           PreparedStatement delIss = sqlCon.prepareStatement(purgeIssues)) {
         int i=0;
         try (ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
               oldJobId[i++] = rs.getInt("dq_job_id");
            }
         } catch (SQLException e) {
            e.printStackTrace();
         }
         // Delete rows for all but last 2 job_id's
         for (int j=0; j<i-2; j++) {
            try {
               delete.setInt(1,oldJobId[j]);
               delete.executeUpdate();
               delIss.setInt(1,oldJobId[j]);
               delIss.executeUpdate();
               commitSql();
               System.out.print(".");
            } catch (SQLException e) {
               e.printStackTrace();
            }
         }
      } catch (SQLException e) {
         e.printStackTrace();
      }
      System.out.println();
   }

   private void dropIndexes() {
      String drop;
      String[] index = getIndexes();
      for (int i=0; index[i] != null; i++) {
         if (!index[i].equals("PRIMARY")) {
            try (Statement stmt = sqlCon.createStatement()) {
               drop = "DROP INDEX " + index[i] + " ON dq_page_analysis;";
               stmt.executeUpdate(drop);
            } catch (SQLException e) {
               e.printStackTrace();
            }
         }
      } 
   }

   private void createIndexes() {
      String[][] indexStmt = new String[2][4];
      indexStmt[0][0] = "dq_title";
      indexStmt[0][1] = "dq_parent_page";
      indexStmt[0][2] = "dq_husband_page";
      indexStmt[0][3] = "dq_wife_page";
      indexStmt[1][0] = "CREATE UNIQUE INDEX dq_title ON dq_page_analysis (dq_title, dq_job_id, dq_namespace);";
      indexStmt[1][1] = "CREATE INDEX dq_parent_page ON dq_page_analysis (dq_parent_page, dq_job_id, dq_namespace);";
      indexStmt[1][2] = "CREATE INDEX dq_husband_page ON dq_page_analysis (dq_husband_page, dq_job_id, dq_namespace);";
      indexStmt[1][3] = "CREATE INDEX dq_wife_page ON dq_page_analysis (dq_wife_page, dq_job_id, dq_namespace);";

      String[] index = getIndexes();
      if (index[3] == null) {
         System.out.print("Creating indexes");
      }
      for (int i=0; i<4; i++) {
         boolean indexExists = false;
         for (int j=0; index[j] != null; j++) {
            if (index[j].equals(indexStmt[0][i])) {
               indexExists = true;
               break;
            }
         }
         if (!indexExists) {
            try (Statement stmt = sqlCon.createStatement()) {
               stmt.executeUpdate(indexStmt[1][i]);
               System.out.print(".");
            } catch (SQLException e) {
               e.printStackTrace();
            }
         }
      }
      if (index[3] == null) {
         System.out.println();
      }
   }

   private String[] getIndexes() {
      String[] index = new String[20];
      String query = "SELECT index_name FROM information_schema.statistics " +
                     "WHERE table_schema = \"wikidb\" AND table_name = \"dq_page_analysis\" " +
                     "AND seq_in_index = 1;";
      try (Statement stmt = sqlCon.createStatement()) {
         try (ResultSet rs = stmt.executeQuery(query)) {
            for (int i=0; rs.next(); i++) {
               index[i] = rs.getString("index_name");
            }
         } catch (SQLException e) {
            e.printStackTrace();
         }
      } catch (SQLException e) {
         e.printStackTrace();
      }
      return index;
   }

   private boolean setJobId() {
      String analysisJob = "SELECT dq_job_id FROM dq_page_analysis ORDER BY dq_job_id DESC LIMIT 1;";
      String issuesJob = "SELECT dq_job_id FROM dq_page ORDER BY dq_job_id DESC LIMIT 1;";
      String statsJob = "SELECT dqs_job_id FROM dq_stats ORDER BY dqs_job_id DESC LIMIT 1;";
      String issueJob = "SELECT dqi_job_id FROM dq_issue ORDER BY dqi_job_id DESC LIMIT 1;";
      ResultSet rs;
      try (Statement stmt = sqlCon.createStatement()) {

         /* If restarting/extending a job, jobId has to be last jobId in dq_page_analysis
            and there cannot be any records in dq_page or dq_stats for this or a later job. */
         if (round>1) {
            rs = stmt.executeQuery(analysisJob);
            if (rs.next()) {
               jobId = rs.getInt("dq_job_id");
            }
            else {
               logger.error("Cannot restart/extend a job because dq_page_analysis is empty");
               return false;
            }
            rs.close();
            rs = stmt.executeQuery(issuesJob);
            if (rs.next()) {
               int lastJobId = rs.getInt("dq_job_id");
               if (jobId <= lastJobId) {
                  logger.error("Cannot restart/extend job " + jobId + "; there are records in dq_page for this or a later job");
                  return false;
               }
            }
            rs.close();
            rs = stmt.executeQuery(statsJob);
            if (rs.next()) {
               int lastJobId = rs.getInt("dqs_job_id");
               if (jobId <= lastJobId) {
                  logger.error("Cannot restart/extend job " + jobId + "; there are records in dq_stats for this or a later job");
                  return false;
               }
            }
            rs.close();
         }

         /* If starting at first round, jobId is 1 higher than last jobId in dq_issue. */
         else {
            rs = stmt.executeQuery(issueJob);
            if (rs.next()) {
               jobId = rs.getInt("dqi_job_id")+1;
            }
            else {
               jobId = 1;
            }
         }
      } catch (SQLException e) {
         e.printStackTrace();
      }
      return true;
   }

   private void writeCacheTime(String cacheTime) {
      String select = "SELECT COUNT(*) AS count FROM querycache_info WHERE qci_type = 'AnalyzeDataQuality';";
      String insert = "INSERT INTO querycache_info (qci_type, qci_timestamp) " +
            "VALUES ('AnalyzeDataQuality', '" + cacheTime + "');";
      String update = "UPDATE querycache_info SET qci_timestamp = '" +
            cacheTime + "' WHERE qci_type = 'AnalyzeDataQuality';";
      try (Statement stmt = sqlCon.createStatement()) {
         ResultSet rs = stmt.executeQuery(select);
         if (rs.next()) {
            if (rs.getInt("count") > 0) {
               stmt.executeUpdate(update);
            }
            else {
               stmt.executeUpdate(insert);
            }
            commitSql();
         }
      } catch (SQLException e) {
         e.printStackTrace();
      }
   }

   private void openSqlConnection(String userName, String password) {
      try {
         Class.forName("com.mysql.jdbc.Driver").newInstance();
         sqlCon = DriverManager.getConnection("jdbc:mysql://localhost/wikidb", userName, password);
         sqlCon.setAutoCommit(false);
      } catch (Exception e) {
         e.printStackTrace();
      }
   }

   private void commitSql() {
      try {
         sqlCon.commit();
      } catch (Exception e) {
         e.printStackTrace();
      }
   }

   private void rollbackSql() {
      try {
         sqlCon.rollback();
      } catch (Exception e) {
         e.printStackTrace();
      }
   }

   private void closeSqlConnection() {
      try {
         sqlCon.close();
      } catch (Exception e) {
         e.printStackTrace();
      }
   }

   // Utility copied from www.java2s.com copyright 2016 under Apache License
   public static Integer getInteger(final ResultSet rs, final String field) throws SQLException {
      if (rs.getObject(field) == null) {
         return null;
      }
      return Integer.valueOf(rs.getInt(field));
   }

   public static String SqlTitle(String title) {
      if (title != null) {
         title = title.replace(" ","_").replace("\"","\\\"").replace("'","\\\'");
      }
      return title;
   }

   // Analyze pages, determine earliest and latest birth year if missing and report errors and anomalies
   // args array: 0=pages.xml 1=username 2=password 3=startround 4=endround
   public static void main(String[] args)
           throws IOException, ParsingException
   {

      // Initialize
      AnalyzeDataQuality self = new AnalyzeDataQuality();
      // By default, go from round 1 to round 4, but user can override (for restartability or extension)
      round = 1;
      if (args.length > 3) {
         round = Integer.parseInt(args[3]);
      }
      if (round==2) {
         logger.error("Cannot restart/extend job at round 2. Start a new job instead.");
      }
      else {
         int endRound = 4;
         if (args.length > 4) {
            endRound = Integer.parseInt(args[4]);
         }
         self.openSqlConnection(args[1], args[2]);
         if (self.setJobId()) {
            logger.info("Job #" + jobId + " started");
   
            // Initial round - create rows in dq_page_analysis table (after truncating the table)
            if (round == 1) {
               WikiReader wikiReader = new WikiReader();
               wikiReader.setSkipRedirects(true);
               wikiReader.addWikiPageParser(self);
               InputStream in = new FileInputStream(args[0]);
   
               self.dropIndexes();
               self.purgeAnalysis();
               try { 
                  wikiReader.read(in);
               } catch (ParsingException e) {
                  e.printStackTrace();
               } finally {
                  self.insertRows(); // Last set of rows
                  self.insertIssueRows();
                  in.close();
               }
               logger.info("Job #" + jobId + " round 1 ended");
               round++;
            }
   
            // Subsequent rounds to derive dates based on dates in immediate family. Create indexes if missing.
            while (round <= endRound) {
               self.createIndexes();
               self.nextRound();
               round++;
            }
   
            // Copy rows indicating an issue to the dq_page table, and output counts of issue types.
            self.copyIssues();
   
            logger.info("Job #" + jobId + " ended");
            self.writeCacheTime(cachedtf.format(startTime.getTime()));
         }
         else {
            logger.error("Job failed");
         }
         self.closeSqlConnection();
      }
      System.exit(0);     // to prevent problems with cleaning up threads (from use of SQL)
   }
}