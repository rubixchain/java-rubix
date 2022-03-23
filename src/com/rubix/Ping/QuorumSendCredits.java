package com.rubix.Ping;

import io.ipfs.api.IPFS;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

import static com.rubix.Resources.Functions.*;
import static com.rubix.Resources.IPFSNetwork.*;

public class QuorumSendCredits {
    public static Logger QuorumPingReceiverLogger = Logger.getLogger(QuorumSendCredits.class);

    private static final JSONObject APIResponse = new JSONObject();
    private static final IPFS ipfs = new IPFS("/ip4/127.0.0.1/tcp/" + IPFS_PORT);

    public static String sendCredits(int port) {
        pathSet();
        ServerSocket ss ;
        Socket sk ;

        try {
            repo(ipfs);


            PropertyConfigurator.configure(LOGGER_PATH + "log4jWallet.properties");

            String receiverPeerID = getPeerID(DATA_PATH + "DID.json");
            String appName = receiverPeerID.concat("Credits");
            listen(appName, port);
            ss = new ServerSocket(port);
            QuorumPingReceiverLogger.debug("Credits Quorum Listening on " + port + " appname " + appName);

            sk = ss.accept();
            QuorumPingReceiverLogger.debug("Data Incoming...");
            BufferedReader input = new BufferedReader(new InputStreamReader(sk.getInputStream()));
            PrintStream output = new PrintStream(sk.getOutputStream());

            String pingRequest;
            try {
                pingRequest = input.readLine();
            } catch (SocketException e) {
                QuorumPingReceiverLogger.warn("Sender Stream Null - Credits");
                APIResponse.put("did", "");
                APIResponse.put("tid", "null");
                APIResponse.put("status", "Failed");
                APIResponse.put("message", "Sender Stream Null - Credits");

                output.close();
                input.close();
                sk.close();
                ss.close();
                return APIResponse.toString();

            }
            QuorumPingReceiverLogger.debug("Credits Request Received: " + pingRequest);
            if(pingRequest != null && pingRequest.contains("Get-Credits")) {
                String qstFile = WALLET_DATA_PATH.concat("QuorumSignedTransactions.json");
                File quorumFile = new File(qstFile);
                int unspentCredits = 0;
                if(quorumFile.exists()){
                    String qFile = readFile(qstFile);
                    JSONArray qArray = new JSONArray(qFile);
                    unspentCredits = qArray.length();
                }
                output.println(unspentCredits);

                APIResponse.put("status", "Success");
                APIResponse.put("message", "Credits Sent");
                QuorumPingReceiverLogger.info("Credits Sent " + unspentCredits);

            }else{
                APIResponse.put("status", "Failed");
                APIResponse.put("message", "Credits Failed");
                QuorumPingReceiverLogger.info("Credits Failed");
            }
            executeIPFSCommands(" ipfs p2p close -t /p2p/" + pingRequest);
            output.close();
            input.close();
            sk.close();
            ss.close();

        } catch (IOException e) {
            e.printStackTrace();
        }
        return APIResponse.toString();
    }
}