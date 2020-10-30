package com.rubix.Consensus;

import com.rubix.AuthenticateNode.Authenticate;
import com.rubix.AuthenticateNode.PropImage;
import com.rubix.Resources.IPFSNetwork;
import io.ipfs.api.IPFS;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.Socket;
import java.util.ArrayList;

import static com.rubix.Resources.Functions.*;
import static com.rubix.Resources.IPFSNetwork.forward;
import static com.rubix.Resources.IPFSNetwork.swarmConnect;


public class InitiatorConsensus {

    public static Logger InitiatorConsensusLogger = Logger.getLogger(InitiatorConsensus.class);


    private static Socket[] qSocket = new Socket[QUORUM_COUNT];
    private static PrintStream[] qOut = new PrintStream[QUORUM_COUNT];
    private static BufferedReader[] qIn = new BufferedReader[QUORUM_COUNT];
    private static String[] qResponse = new String[QUORUM_COUNT];

    public static volatile JSONObject quorumSignature = new JSONObject();
    private static final Object countLock = new Object();
    private static final Object signLock = new Object();
    private static String[] quorumID = new String[QUORUM_COUNT];
    public static ArrayList<String> quorumWithShares = new ArrayList<>();
    public static volatile int quorumResponse = 0;



    /**
     * This method increments the quorumResponse variable
     */
    private static synchronized void voteNCount() {
        PropertyConfigurator.configure(LOGGER_PATH + "log4jWallet.properties");
        synchronized (countLock) {
            quorumResponse++;
            if (quorumResponse >= minQuorum()) {
                InitiatorConsensusLogger.debug("Consensus Reached");
            }
        }
    }

    /**
     * This method stores all the quorum signatures until required count for consensus
     * @param quorumDID DID of the Quorum
     * @param quorumResponse Signature of the Quorum
     */
    private static synchronized void quorumSign(String quorumDID, String quorumResponse) {
        PropertyConfigurator.configure(LOGGER_PATH + "log4jWallet.properties");
        synchronized (signLock) {
            try {
                quorumSignature.put(quorumDID, quorumResponse);
                if(quorumSignature.length() >= minQuorum())
                    InitiatorConsensusLogger.debug("Signatures length " + quorumSignature.length());
            } catch (JSONException e) {
                InitiatorConsensusLogger.error("JSON Exception Occurred", e);
                e.printStackTrace();
            }
        }
    }


    /**
     * This method runs the consensus
     * 1. Contact quorum with sender signatures and details
     * 2. Verify quorum signatures
     * 3. If consensus reached , sends shares to Quorum
     * @param hash Data for the quorum to sign on
     * @param details Data to be shared with Quorum
     * @param quorumPeersObject List of quorum peers to be contacted
     * @param ipfs IPFS instance
     * @param PORT Port for forwarding to Quorum
     */
    public static void start(String hash, JSONArray details,ArrayList<String> quorumPeersObject,IPFS ipfs, int PORT) {
        PropertyConfigurator.configure(LOGGER_PATH + "log4jWallet.properties");
        quorumResponse = 0;
        quorumSignature = new JSONObject();
        JSONArray tokenDetails;
        try {
            tokenDetails = new JSONArray(details.toString());
            JSONObject detailsToken = tokenDetails.getJSONObject(0);
            JSONObject sharesToken = tokenDetails.getJSONObject(1);

            String[] shares = new String[minQuorum() - 1];
            for(int i = 0; i < shares.length; i++){
                int p = i + 1;
                shares[i] = sharesToken.getString("Share" + p);
            }

            for (int j = 0; j < quorumPeersObject.size(); j++) {
                quorumID[j] = quorumPeersObject.get(j);

                String quorumDidIpfsHash = getValues(DATA_PATH + "DataTable.json", "didHash", "peerid", quorumID[j]);
                String quorumWidIpfsHash = getValues(DATA_PATH + "DataTable.json", "walletHash", "peerid", quorumID[j]);

                File quorumDataFolder = new File(DATA_PATH + quorumDidIpfsHash + "/");
                if (!quorumDataFolder.exists()) {
                    quorumDataFolder.mkdirs();
                    IPFSNetwork.getImage(quorumDidIpfsHash, ipfs, DATA_PATH + quorumDidIpfsHash + "/DID.png" );
                    IPFSNetwork.getImage(quorumWidIpfsHash, ipfs, DATA_PATH + quorumDidIpfsHash + "/PublicShare.png" );
                    InitiatorConsensusLogger.debug("Quorum Data " + quorumID[j] + " Added");
                }
                else
                    InitiatorConsensusLogger.debug("Quorum Data " + quorumID[j] + " Available");
            }



            Thread[] quorumThreads = new Thread[quorumPeersObject.size()];
            for (int i = 0; i < quorumPeersObject.size(); i++) {
                int j = i;
                int k = i+1;
                quorumThreads[i] = new Thread(() -> {

                    try {
                        swarmConnect(quorumID[j],ipfs);
                        String appName = quorumID[j].concat("consensus");
                        forward(appName, PORT+j, quorumID[j]);
                        InitiatorConsensusLogger.debug("Connected to " + quorumID[j] + "on AppName" + appName);
                        qSocket[j] = new Socket("127.0.0.1", PORT+j);
                        qIn[j] = new BufferedReader(new InputStreamReader(qSocket[j].getInputStream()));
                        qOut[j] = new PrintStream(qSocket[j].getOutputStream());
                        qOut[j].println(detailsToken);

                        qResponse[j] = qIn[j].readLine();
                        InitiatorConsensusLogger.debug("Signature Received from " + quorumID[j] + " " + qResponse[j]);

                        if (quorumResponse > minQuorum()) {
                            qOut[j].println("null");
                            IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /ipfs/"+ quorumID[j]);
                        } else {

                            String didHash = getValues(DATA_PATH + "DataTable.json", "didHash", "peerid",quorumID[j]);

                            BufferedImage didImage = ImageIO.read(new File(DATA_PATH + didHash + "/DID.png"));
                            BufferedImage widImage = ImageIO.read(new File(DATA_PATH + didHash + "/PublicShare.png"));

                            String decentralisedID = PropImage.img2bin(didImage);
                            String walletID = PropImage.img2bin(widImage);

                            JSONObject detailsToVerify = new JSONObject();
                            detailsToVerify.put("did", didHash);
                            if (Authenticate.verifySignature(detailsToVerify.toString(), qResponse[j])) {
                                voteNCount();
                                if (quorumResponse < minQuorum()) {
                                    String shareToQuorum = shares[quorumResponse - 1];
                                    while (quorumResponse < minQuorum()) {
                                        Thread.onSpinWait();
                                    }
                                    quorumSign( didHash, qResponse[j]);
                                    quorumWithShares.add(quorumPeersObject.get(j));
                                    qOut[j].println(shareToQuorum);
                                    IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /ipfs/"+ quorumID[j]);

                                } else if (quorumResponse == minQuorum()) {
                                    quorumSign(didHash, qResponse[j]);
                                    qOut[j].println("null");
                                    IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /ipfs/"+ quorumID[j]);

                                } else {
                                    qOut[j].println("null");
                                    IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /ipfs/"+ quorumID[j]);
                                }
                                InitiatorConsensusLogger.debug("Quorum Count : " + quorumResponse + "Signature count : " + quorumSignature.length());

                            }
                        }
                    } catch (IOException | JSONException e) {
                        IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /ipfs/"+ quorumID[j]);
                        InitiatorConsensusLogger.error("IOException Occurred", e);
                        e.printStackTrace();
                    }
                });
                quorumThreads[j].start();
            }
            while(quorumResponse < minQuorum() || quorumSignature.length() < minQuorum()){

            }
        } catch (JSONException e) {
            InitiatorConsensusLogger.error("JSON Exception Occurred", e);
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
