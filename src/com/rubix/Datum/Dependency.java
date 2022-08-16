package com.rubix.Datum;

import static com.rubix.Resources.Functions.DATA_PATH;

import java.util.HashMap;
import java.util.Map.Entry;

import com.rubix.Resources.Functions;

import org.json.JSONArray;

public class Dependency {

	public static HashMap<String, String> dataTableHashMap() {
		String dataTableContent = Functions.readFile(
				DATA_PATH + "DataTable.json");
        JSONArray dataTableArray = new JSONArray(dataTableContent);
        HashMap<String, String> dataTableMap = new HashMap<String, String>();
        for (int ctr = 0; ctr < dataTableArray.length(); ctr++) {
            dataTableMap.put(dataTableArray.getJSONObject(ctr).get("didHash").toString(),
                    dataTableArray.getJSONObject(ctr).get("peerid").toString());
        }
        return dataTableMap;

	}
	public static HashMap<String, String> widDataTableHashMap() {
		String dataTableContent = Functions.readFile(
				DATA_PATH + "DataTable.json");
        JSONArray dataTableArray = new JSONArray(dataTableContent);
        HashMap<String, String> dataTableMap = new HashMap<String, String>();
        for (int ctr = 0; ctr < dataTableArray.length(); ctr++) {
            dataTableMap.put(dataTableArray.getJSONObject(ctr).get("peerid").toString(),
                    dataTableArray.getJSONObject(ctr).get("walletHash").toString());
        }
        return dataTableMap;

	}
	
	public static String getPIDfromDID(String did,HashMap<String, String>dataTable) {
		String pidString = "";
		if (dataTable.containsKey(did)) {
			pidString = dataTable.get(did);
        } else {
        	pidString = "Not Found";
        }
		return pidString;
	}
	
	public static String getDIDfromPID(String pid,HashMap<String, String>dataTable) {
		String didString = "Not Found";
		for (Entry<String, String> entry : dataTable.entrySet()) {
            if (entry.getValue().equals(pid)) {
                didString = entry.getKey();
                System.out.println("The key for value " + pid + " is " + entry.getKey());
                break;
            }
        }
		return didString;
		}
	
	public static String getWIDfromPID(String pid,HashMap<String, String>dataTable) {
		String widString = "";
		if (dataTable.containsKey(pid)) {
			widString = dataTable.get(pid);
        } else {
        	widString = "Not Found";
        }
		return widString;
	}
	

}
