package com.rubix.LevelDb;

import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBIterator;

import static org.iq80.leveldb.impl.Iq80DBFactory.factory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import static com.rubix.Resources.Functions.*;

import org.iq80.leveldb.Options;
import org.json.simple.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.simple.parser.JSONParser;

public class DataBase {

    public static DB transactionHistory = null;
    public static DB essentialShare = null;
    public static DB levelDb;
    public static DB quorumSignedTransaction=null;
    public static DB quorumSign=null;

    /*
     * public static DB createDB(String dbName) throws IOException {
     * pathSet();
     * 
     * Options options = new Options();
     * levelDb = factory.open(new File(dbName), options);
     * return levelDb;
     * }
     */
    public static void createOrOpenDB() {
        pathSet();

        try {
            Options options = new Options();
            transactionHistory = factory.open(new File(WALLET_DATA_PATH + "transactionHistory"), options);
            essentialShare = factory.open(new File(WALLET_DATA_PATH + "essentialShare"), options);
            quorumSignedTransaction=factory.open(new File(WALLET_DATA_PATH + "quorumSignedTransaction"),options);
            quorumSign=factory.open(new File(WALLET_DATA_PATH + "quorumSign"),options);

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public static void putDataTransactionHistory(String key, String value) {
        transactionHistory.put(key.getBytes(), value.getBytes());
    }

    public static void putDataEssentialShare(String key, String value) {
        essentialShare.put(key.getBytes(), value.getBytes());
    }

    public static void putDataQuorumSignTxn(String key, String value)
    {
        quorumSignedTransaction.put(key.getBytes(), value.getBytes());
    }
    
    public static void putDataQuorumSign(String key, String value)
    {
        quorumSign.put(key.getBytes(), value.getBytes());
    }

    public static JSONObject getData(String key, DB database) throws JSONException {
        String value = new String(database.get(key.getBytes()));
        JSONObject jsonValue = new JSONObject(value);
        return jsonValue;
    }

    public static String getDatabyTxnId(String txnId) {

        String resultTxn = null, txnHis, essShr;
        JSONObject resultTxnObj = new JSONObject();

        try {
            txnHis = new String(transactionHistory.get(txnId.getBytes()));
            essShr = new String(essentialShare.get(txnId.getBytes()));

            JSONObject tempTxnhis = new JSONObject(txnHis);
            JSONObject tempEssShr = new JSONObject(essShr);

            resultTxnObj.put("senderDID", tempTxnhis.get("senderDID"));
            resultTxnObj.put("role", tempTxnhis.get("role"));
            resultTxnObj.put("totalTime", tempTxnhis.get("totalTime"));
            resultTxnObj.put("quorumList", tempTxnhis.get("quorumList"));
            resultTxnObj.put("tokens", tempTxnhis.get("tokens"));
            resultTxnObj.put("comment", tempTxnhis.get("comment"));
            resultTxnObj.put("txn", tempTxnhis.get("txn"));
            resultTxnObj.put("essentialShare", tempEssShr.get("essentialShare"));
            resultTxnObj.put("receiverDID", tempTxnhis.get("receiverDID"));
            resultTxnObj.put("Date", tempTxnhis.get("Date"));

            resultTxn = resultTxnObj.toString();
        } catch (NullPointerException e) {
            System.out.println("No Transaction Found / Please check TransactionID");
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return resultTxn;

    }

    public static void pushTxnFiletoDB() {
        FileReader fr;
        try {
            fr = new FileReader(WALLET_DATA_PATH + "TransactionHistory.json");
            JSONParser jsonParser = new JSONParser();
            JSONArray jsonArray = (JSONArray) jsonParser.parse(fr);
            for (Object o : jsonArray) {
                org.json.simple.JSONObject obj = (org.json.simple.JSONObject) o;
                org.json.simple.JSONObject value1 = new org.json.simple.JSONObject();
                org.json.simple.JSONObject value2 = new org.json.simple.JSONObject();
                value1.put("senderDID", obj.get("senderDID"));
                value1.put("role", obj.get("role"));
                value1.put("totalTime", obj.get("totalTime"));
                value1.put("quorumList", obj.get("quorumList"));
                value1.put("tokens", obj.get("tokens"));
                value1.put("comment", obj.get("comment"));
                value1.put("txn", obj.get("txn"));
                value1.put("receiverDID", obj.get("receiverDID"));
                value1.put("Date", obj.get("Date"));

                value2.put("essentialShare", obj.get("essentialShare"));

                putDataTransactionHistory(obj.get("txn").toString(), value1.toString());

                putDataEssentialShare(obj.get("txn").toString(), value2.toString());

                fr.close();
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String getAllTxn() {
        String resultStr = null, valueES = null, valueTH = null;
        org.json.JSONArray resultArray = new org.json.JSONArray();

        try {
            DBIterator iteratorTH = transactionHistory.iterator();

            while (iteratorTH.hasNext()) {
                byte[] key = iteratorTH.peekNext().getKey();
                valueTH = new String(transactionHistory.get(key));
                valueES = new String(essentialShare.get(key));

                JSONObject tempObj1 = new JSONObject(valueTH);
                JSONObject tempObj2 = new JSONObject(valueES);

                JSONObject resultObj = new JSONObject();

                resultObj.put("senderDID", tempObj1.get("senderDID"));
                resultObj.put("role", tempObj1.get("role"));
                resultObj.put("totalTime", tempObj1.get("totalTime"));
                resultObj.put("quorumList", tempObj1.get("quorumList"));
                resultObj.put("tokens", tempObj1.get("tokens"));
                resultObj.put("comment", tempObj1.get("comment"));
                resultObj.put("txn", tempObj1.get("txn"));
                resultObj.put("essentialShare", tempObj2.get("essentialShare"));
                resultObj.put("receiverDID", tempObj1.get("receiverDID"));
                resultObj.put("Date", tempObj1.get("Date"));

                resultArray.put(resultObj);

                //resultStr = resultArray.toString();

                iteratorTH.next();

            }

            resultStr = resultArray.toString();
        } catch (NullPointerException e) {
            System.out.println("No Transaction details found");
        } catch (JSONException e) {
            e.printStackTrace();
        }

        return resultStr;

    }

    public static String sortedTxnDetails() {
        String resultString = null;
        String txnDetails=getAllTxn();

        try {
            org.json.JSONArray jsonTxnDetails = new org.json.JSONArray(txnDetails);
            List<JSONObject> list = new ArrayList<JSONObject>();
            for (int i = 0; i < jsonTxnDetails.length(); i++) {
                list.add(jsonTxnDetails.getJSONObject(i));
            }
            Collections.sort(list, new sortBasedOnDate());

            org.json.JSONArray sortedArray = new org.json.JSONArray(list);

            return sortedArray.toString();

        } catch (JSONException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        return resultString;
    }

    public static void pushQstDatatoDB()
    {
        FileReader fr;
        int counter=0;
        try {
            fr = new FileReader(WALLET_DATA_PATH + "QuorumSignedTransactions.json");
            JSONParser jsonParser = new JSONParser();
            JSONArray jsonArray = (JSONArray) jsonParser.parse(fr);
            for (Object o : jsonArray) {
                JSONObject obj1 = (JSONObject) o;
                JSONObject obj2 = new JSONObject();
                JSONObject obj3 = new JSONObject();
                obj2.put("senderdid", obj1.get("senderdid"));
                obj2.put("credits", obj1.get("credits"));
                obj2.put("tid", obj1.get("tid"));
                obj2.put("minestatus", obj1.get("minestatus"));
                obj2.put("consensusID", obj1.get("consensusID"));
                obj2.put("serialNoQst", counter);

                obj3.put("sign", obj1.get("sign"));
                obj3.put("serialNoQsign", counter);

                quorumSignedTransaction.put(obj1.get("tid").toString().getBytes(), obj2.toString().getBytes());
                quorumSign.put(obj1.get("tid").toString().getBytes(), obj3.toString().getBytes());
                counter++;

                fr.close();
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String getAllQstData() {
        String result = null, valueQst = null, valueQsign = null;
        org.json.JSONArray resultArray = new org.json.JSONArray();

        try {
            DBIterator iteratorQst = quorumSignedTransaction.iterator();
            while (iteratorQst.hasNext()) {
                byte[] key = iteratorQst.peekNext().getKey();
                valueQst = new String(quorumSignedTransaction.get(key));
                valueQsign = new String(quorumSign.get(key));

                org.json.JSONObject obj1 = new org.json.JSONObject(valueQst);
                org.json.JSONObject obj2 = new org.json.JSONObject(valueQsign);

                org.json.JSONObject resultObj = new org.json.JSONObject();

                resultObj.put("senderdid", obj1.get("senderdid"));
                resultObj.put("credits", obj1.get("credits"));
                resultObj.put("sign", obj2.get("sign"));
                resultObj.put("tid", obj1.get("tid"));
                resultObj.put("minestatus", obj1.get("minestatus"));
                resultObj.put("consensusID", obj1.get("consensusID"));
                resultObj.put("serialNoQst", obj1.get("serialNoQst"));
                resultObj.put("serialNoQsign", obj2.get("serialNoQsign"));

                resultArray.put(resultObj);

                iteratorQst.next();

            }
            result = resultArray.toString();

        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }

        return result;
    }

    public static String sortedQstData() {
        String resultString = null;
        String qstDetails = getAllQstData();

        try {
            org.json.JSONArray jsonQstDetails = new org.json.JSONArray(qstDetails);
            List<org.json.JSONObject> list = new ArrayList<org.json.JSONObject>();
            for (int i = 0; i < jsonQstDetails.length(); i++) {
                list.add(jsonQstDetails.getJSONObject(i));
            }
            Collections.sort(list, new sortBasedOnSerialNo());

            org.json.JSONArray sortedArray = new org.json.JSONArray(list);

            return sortedArray.toString();
        } catch (Exception e) {
            // TODO: handle exception
        }

        return resultString;
    }

    public static void closeDB()
    {
        try {
            transactionHistory.close();
            essentialShare.close();
            quorumSignedTransaction.close();
            quorumSign.close();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        
    }

}
