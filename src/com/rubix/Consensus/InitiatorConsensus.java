package com.rubix.Consensus;

import static com.rubix.Constants.ConsensusConstants.INIT_HASH;
import static com.rubix.Resources.IPFSNetwork.forward;
import static com.rubix.Resources.IPFSNetwork.repo;
import static com.rubix.Resources.IPFSNetwork.swarmConnectP2P;

import static com.rubix.Resources.Functions.*;
import static com.rubix.NFTResources.NFTFunctions.*;
import static com.rubix.Resources.APIHandler.getPubKeyIpfsHash_DIDserver;

import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;

import com.rubix.AuthenticateNode.Authenticate;
import com.rubix.Resources.IPFSNetwork;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import io.ipfs.api.IPFS;

public class InitiatorConsensus {

    public static Logger InitiatorConsensusLogger = Logger.getLogger(InitiatorConsensus.class);

    public static volatile JSONArray quorumSignature = new JSONArray();
    public static volatile JSONArray signedAphaQuorumArray = new JSONArray();
    private static final Object countLock = new Object();
    private static final Object signLock = new Object();
    public static ArrayList<String> quorumWithShares = new ArrayList<>();
    public static volatile int[] quorumResponse = { 0, 0, 0 };
    public static volatile JSONArray finalQuorumSignsArray = new JSONArray();




    public static volatile JSONObject nftQuorumSignature = new JSONObject();
    private static final Object nftCountLock = new Object();
    private static final Object nftSignLock = new Object();
    public static ArrayList<String> nftQuorumWithShares = new ArrayList<>();
    public static volatile int[] nftQuorumResponse = { 0, 0, 0 };
    public static volatile JSONArray nftFinalQuorumSignsArray = new JSONArray();

    /**
     * Added by Anuradha K on 04/01/20222.
     * To address SocketConnection reset Issue
     */
    private static int socketTimeOut = 120000;


    /**
     * This method increments the quorumResponse variable
     */
    private static synchronized boolean voteNCount(int i, int quorumSize) {
        boolean status;
        PropertyConfigurator.configure(LOGGER_PATH + "log4jWallet.properties");
        synchronized (countLock) {
            if (quorumResponse[i] < minQuorum(quorumSize)) {
                quorumResponse[i]++;
                InitiatorConsensusLogger.debug("quorum response added index " + i + "  is " + quorumResponse[i]
                        + " quorumsize " + minQuorum(quorumSize));
                status = true;
            } else {
                status = false;
                InitiatorConsensusLogger.debug("Consensus Reached for index " + i);
            }
        }
        return status;
    }

    /**
     * This method increments the nftQuorumResponse variable
     */
    private static synchronized boolean voteNftNCount(int i, int quorumSize) {
        boolean status;
        PropertyConfigurator.configure(LOGGER_PATH + "log4jWallet.properties");
        synchronized (nftCountLock) {
            if (nftQuorumResponse[i] < minQuorum(quorumSize)) {
                nftQuorumResponse[i]++;
                InitiatorConsensusLogger.debug("NFT quorum response added index " + i + "  is " + nftQuorumResponse[i]
                        + " quorumsize " + minQuorum(quorumSize));
                status = true;
            } else {
                status = false;
                InitiatorConsensusLogger.debug("NFT Consensus Reached for index " + i);
            }
        }
        return status;
    }

    /**
     * This method stores all the quorum signatures until required count for
     * consensus
     *
     * @param quorumDID          DID of the Quorum
     * @param quorumSignResponse Signature of the Quorum
     */
    private static synchronized void quorumSign(String quorumDID, String hash, String quorumSignResponse, String pvtKeySign, int index,
            int quorumSize, int alphaSize) {
        PropertyConfigurator.configure(LOGGER_PATH + "log4jWallet.properties");
        synchronized (signLock) {
            try {
                if (quorumSignature.length() < (minQuorum(alphaSize))
                        && quorumResponse[index] <= minQuorum(quorumSize)) {
                    JSONObject jsonObject = new JSONObject();
                    JSONObject quorumMemberSign = new JSONObject();
                    jsonObject.put("did", quorumDID);
                    jsonObject.put("signature", quorumSignResponse);
                    jsonObject.put("hash", hash);
                    finalQuorumSignsArray.put(jsonObject);

                    quorumMemberSign.put("quorum_did",quorumDID);
                    quorumMemberSign.put("quorumPrivateShareSign", quorumSignResponse);
                    quorumMemberSign.put("quorumPvtKeySign",pvtKeySign);

                    quorumSignature.put(quorumMemberSign);
                } else {
                    InitiatorConsensusLogger.debug("quorum already reached consensus " + quorumSignature.length());
                }
            } catch (JSONException e) {
                InitiatorConsensusLogger.error("JSON Exception Occurred", e);
                e.printStackTrace();
            }
        }
    }

    /**
     * This method stores all the quorum signatures until required count for
     * consensus
     *
     * @param quorumDID          DID of the Quorum
     * @param quorumSignResponse Signature of the Quorum
     */
    private static synchronized void nftQuorumSign(String quorumDID, String hash, String nftQuorumSignResponse,
            int index,
            int quorumSize, int alphaSize) {
        PropertyConfigurator.configure(LOGGER_PATH + "log4jWallet.properties");
        synchronized (nftSignLock) {
            try {
                if (nftQuorumSignature.length() < (minQuorum(alphaSize) + 2 * minQuorum(7))
                        && nftQuorumResponse[index] <= minQuorum(quorumSize)) {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("did", quorumDID);
                    jsonObject.put("signature", nftQuorumSignResponse);
                    jsonObject.put("hash", hash);
                    nftFinalQuorumSignsArray.put(jsonObject);
                    nftQuorumSignature.put(quorumDID, nftQuorumSignResponse);
                } else {
                    InitiatorConsensusLogger
                            .debug("NFT quorum already reached consensus " + nftQuorumSignature.length());
                }
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
     *
     * @param ipfs IPFS instance
     * @param PORT Port for forwarding to Quorum
     */
    public static JSONArray start(String data, IPFS ipfs, int PORT, int index, String role,
            JSONArray quorumPeersObject, int alphaSize, int quorumSize, String operation) throws JSONException {
        String[] qResponse = new String[QUORUM_COUNT];
        Socket[] qSocket = new Socket[QUORUM_COUNT];
        PrintStream[] qOut = new PrintStream[QUORUM_COUNT];
        BufferedReader[] qIn = new BufferedReader[QUORUM_COUNT];
        String[] quorumID = new String[QUORUM_COUNT];
        PropertyConfigurator.configure(LOGGER_PATH + "log4jWallet.properties");
        JSONObject dataObject = new JSONObject(data);
        String hash = dataObject.getString("hash");
        JSONArray details = dataObject.getJSONArray("details");

        quorumResponse[index] = 0;
        InitiatorConsensusLogger.debug("quorum peer role " + role + " length " + quorumPeersObject.length());
        JSONArray tokenDetails;
        try {
            tokenDetails = new JSONArray(details.toString());
            JSONObject detailsToken = tokenDetails.optJSONObject(0);
            JSONObject sharesToken = tokenDetails.optJSONObject(1);

            String[] shares = new String[minQuorum(7) - 1];
            for (int i = 0; i < shares.length; i++) {
                int p = i + 1;
                shares[i] = sharesToken.getString("Share" + p);
            }

            for (int j = 0; j < quorumPeersObject.length(); j++)
                quorumID[j] = quorumPeersObject.getString(j);

            Thread[] quorumThreads = new Thread[quorumPeersObject.length()];
            for (int i = 0; i < quorumPeersObject.length(); i++) {
                int j = i;
                quorumThreads[i] = new Thread(() -> {
                    try {
                        swarmConnectP2P(quorumID[j], ipfs);
                        syncDataTable(null, quorumID[j]);
                        String quorumDidIpfsHash = getValues(DATA_PATH + "DataTable.json", "didHash", "peerid",
                                quorumID[j]);
                        String quorumWidIpfsHash = getValues(DATA_PATH + "DataTable.json", "walletHash", "peerid",
                                quorumID[j]);
                        nodeData(quorumDidIpfsHash, quorumWidIpfsHash, ipfs);
                        String appName = quorumID[j].concat(role);
                        InitiatorConsensusLogger.debug("quourm ID " + quorumID[j] + " appname " + appName);
                        forward(appName, PORT + j, quorumID[j]);
                        InitiatorConsensusLogger.debug(
                                "Connected to " + quorumID[j] + "on port " + (PORT + j) + "with AppName" + appName);

                        qSocket[j] = new Socket("127.0.0.1", PORT + j);
                        qSocket[j].setSoTimeout(socketTimeOut);
                        qIn[j] = new BufferedReader(new InputStreamReader(qSocket[j].getInputStream()));
                        qOut[j] = new PrintStream(qSocket[j].getOutputStream());

                        qOut[j].println(operation);

                        if (operation.equals("new-credits-mining")) {
                            JSONObject qstDetails = dataObject.getJSONObject("qstDetails");
                            qstDetails.put(INIT_HASH, initHash());
                            // Verify QST Credits
                            qOut[j].println(qstDetails.toString());
                            try {
                                qResponse[j] = qIn[j].readLine();
                            } catch (SocketException e) {
                                InitiatorConsensusLogger.warn("Quorum " + quorumID[j] + " is unable to Respond!");
                                IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                            }
                            if (qResponse[j] != null) {
                                if (qResponse[j].equals("Verified")) {
                                    qOut[j].println(detailsToken);
                                    try {
                                        qResponse[j] = qIn[j].readLine();
                                    } catch (SocketException e) {
                                        InitiatorConsensusLogger
                                                .warn("Quorum " + quorumID[j] + " is unable to Respond!");
                                        IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                    }
                                    if (qResponse[j] != null) {
                                        if (qResponse[j].equals("Auth_Failed")) {
                                            IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                        } else {
                                            InitiatorConsensusLogger.debug(
                                                    "Signature Received from " + quorumID[j] + " " + qResponse[j]);
                                            if (quorumResponse[index] > minQuorum(quorumSize)) {
                                                qOut[j].println("null");
                                                IPFSNetwork
                                                        .executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                            } else {
                                                String didHash = getValues(DATA_PATH + "DataTable.json", "didHash",
                                                        "peerid", quorumID[j]);
                                                JSONObject detailsToVerify = new JSONObject();
                                                detailsToVerify.put("did", didHash);
                                                detailsToVerify.put("hash", hash);
                                                detailsToVerify.put("signature", qResponse[j]);
                                               InitiatorConsensusLogger.debug("Hash to check "+detailsToVerify.toString());
                                               InitiatorConsensusLogger.debug(" ");

                                                if (Authenticate.verifySignature(detailsToVerify.toString())) {
                                                    InitiatorConsensusLogger
                                                            .debug(role + " node authenticated at index " + index);
                                                    boolean voteStatus = voteNCount(index, quorumSize);
                                                    if (quorumResponse[index] <= minQuorum(quorumSize) && voteStatus) {
                                                        InitiatorConsensusLogger.debug(
                                                                "waiting for  " + quorumSize + " +signs " + role);
                                                        if (role.equals("alpha")) {
                                                            InitiatorConsensusLogger
                                                                    .debug("Picking Quorum for Staking " + quorumID[j]);
															signedAphaQuorumArray.put(quorumID[j]);
                                                        }
                                                        while (quorumResponse[index] < minQuorum(quorumSize)) {
                                                        }
                                                        InitiatorConsensusLogger.debug("between Q1- to Q" + quorumSize
                                                                + " for index " + index);
                                                        quorumSign(didHash, hash, qResponse[j], "", index, quorumSize,
                                                                alphaSize);
                                                        quorumWithShares.add(quorumPeersObject.getString(j));
                                                        while (quorumSignature
                                                                .length() < (minQuorum(alphaSize) + 2 * minQuorum(7))) {
                                                        }
                                                        InitiatorConsensusLogger.debug("sending Qsign  of length "
                                                                + quorumSignature.length() + "at index " + index);
                                                        qOut[j].println(finalQuorumSignsArray.toString());
                                                        IPFSNetwork.executeIPFSCommands(
                                                                "ipfs p2p close -t /p2p/" + quorumID[j]);
                                                    } else {
                                                        InitiatorConsensusLogger.debug("sending null for slow quorum ");
                                                        qOut[j].println("null");
                                                        IPFSNetwork.executeIPFSCommands(
                                                                "ipfs p2p close -t /p2p/" + quorumID[j]);
                                                    }
                                                    InitiatorConsensusLogger.debug("Quorum Count : " + quorumResponse
                                                            + "Signature count : " + quorumSignature.length());
                                                } else {
                                                    InitiatorConsensusLogger
                                                            .debug("node failed authentication with index " + index
                                                                    + " with role " + role + " with did " + didHash
                                                                    + " and data to verify " + detailsToVerify);
                                                    IPFSNetwork.executeIPFSCommands(
                                                            "ipfs p2p close -t /p2p/" + quorumID[j]);
                                                }
                                            }
                                        }
                                    }

                                } else if (qResponse[j].equals("440")) {
                                    InitiatorConsensusLogger.debug("Credit Verification failed: Duplicates found");
                                    IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                } else if (qResponse[j].equals("441")) {
                                    InitiatorConsensusLogger
                                            .debug("Credit Verification failed: Signature(s) verification failed");
                                    IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                } else if (qResponse[j].equals("442")) {
                                    InitiatorConsensusLogger.debug("Credit Verification failed: Credits hash mismatch");
                                    IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                } else if (qResponse[j].equals("443")) {
                                    InitiatorConsensusLogger.debug("Credit Verification failed: Init hash mismatch");
                                    IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                }
                            }

                        } else if (operation.equals("NFT")) {
                            InitiatorConsensusLogger.debug("NFT Transaction");
                            JSONObject nftDetails = dataObject.getJSONObject("nftDetails");

                            qOut[j].println(nftDetails);

                            try {
                                qResponse[j] = qIn[j].readLine();
                            } catch (SocketException e) {
                                InitiatorConsensusLogger.warn("Quorum " + quorumID[j] + " is unable to Respond!");
                                IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                            }

                            if (qResponse[j] != null) {
                                if (qResponse[j].equals("Buyer_Not_Verified")) {
                                    InitiatorConsensusLogger.debug("NFT Buyer Authentication Failure at " + quorumID[j]);
                                    IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                } else {
                                    InitiatorConsensusLogger
                                            .debug("Quorum Verified NFT Buyer " + quorumID[j] + " " + qResponse[j]);
                                }
                            }

                            try {
                                qResponse[j] = qIn[j].readLine();
                            } catch (SocketException e) {
                                InitiatorConsensusLogger.warn("Quorum " + quorumID[j] + " is unable to Respond!");
                                IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                            }

                            if (qResponse[j] != null) {
                                if (qResponse[j].equals("NFT_Sig_Auth_Failed")) {
                                    InitiatorConsensusLogger.debug("NFT Sign Authentication Failure at " + quorumID[j]);
                                    InitiatorConsensusLogger.debug("NFT Sale and Sender signature verification failed");
                                    IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                } else {
                                    InitiatorConsensusLogger
                                            .debug("Quorum Verified NFT Sale and Sender" + quorumID[j] + " " + qResponse[j]);
                                    if (nftQuorumResponse[index] > minQuorum(quorumSize)) {
                                        qOut[j].println("null");
                                        // IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                    } else {
                                        String nftHash = nftDetails.getString("nftHash");
                                        String quorumSignValue = calculateHash(
                                                nftHash.concat(nftDetails.getString("nftBuyerDid")), "SHA3-256");
                                        String didHash = getValues(DATA_PATH + "DataTable.json", "didHash", "peerid",
                                                quorumID[j]);
                                        JSONObject detailsToVerify = new JSONObject();
                                        detailsToVerify.put("did", didHash);
                                        detailsToVerify.put("hash", quorumSignValue);
                                        // detailsToVerify.put("nftDetails", nftDetails);
                                        detailsToVerify.put("signature", qResponse[j]);
                                        if (Authenticate.verifySignature(detailsToVerify.toString())) {
                                            InitiatorConsensusLogger
                                                    .debug(role + " node authenticated at index " + index);
                                            boolean voteNftStatus = voteNftNCount(index, quorumSize);
                                            if (nftQuorumResponse[index] <= minQuorum(quorumSize) && voteNftStatus) {
                                                InitiatorConsensusLogger
                                                        .debug("waiting for  " + quorumSize + " +signs " + role);
                                                while (nftQuorumResponse[index] < minQuorum(quorumSize)) {
                                                }
                                                InitiatorConsensusLogger
                                                        .debug("between Q1- to Q" + quorumSize + " for index " + index);
                                                nftQuorumSign(didHash, quorumSignValue, qResponse[j], index, quorumSize,
                                                        alphaSize);
                                                nftQuorumWithShares.add(quorumPeersObject.getString(j));
                                                while (nftQuorumSignature
                                                        .length() < (minQuorum(alphaSize) + 2 * minQuorum(7))) {
                                                }
                                                InitiatorConsensusLogger.debug("NFT :sending Qsign  of length "
                                                        + nftQuorumSignature.length() + "at index " + index);
                                                qOut[j].println(nftFinalQuorumSignsArray.toString());
                                                IPFSNetwork
                                                        .executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                            } else {
                                                InitiatorConsensusLogger.debug("sending null for slow quorum ");
                                                qOut[j].println("null");
                                                IPFSNetwork
                                                        .executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                            }
                                            InitiatorConsensusLogger.debug("Quorum Count : " + nftQuorumResponse
                                                    + "Signature count : " + nftQuorumSignature.length());
                                        } else {
                                            InitiatorConsensusLogger.debug("node failed authentication with index "
                                                    + index + " with role " + role + " with did " + didHash
                                                    + " and data to verify " + detailsToVerify);
                                            IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                        }
                                    }

                                }
                            }
                            InitiatorConsensusLogger.debug("NFT quorumSignatures length" + nftQuorumSignature.length());

                            while ((nftQuorumResponse[index] < minQuorum(quorumSize)
                                            || nftQuorumSignature
                                                    .length() < (minQuorum(alphaSize) + 2 * minQuorum(7)))) {
                            }

                        } else {

                            qOut[j].println(detailsToken);

                            try {
                                qResponse[j] = qIn[j].readLine();
                            } catch (SocketException e) {
                                InitiatorConsensusLogger.warn("Quorum " + quorumID[j] + " is unable to Respond!");
                                IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                            }

                            if (qResponse[j] != null) {
                                if (qResponse[j].equals("Auth_Failed")) {
                                    InitiatorConsensusLogger.debug("Sender Authentication Failure at " + quorumID[j]);
                                    IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                } else {
                                    InitiatorConsensusLogger
                                            .debug("Signature Received from " + quorumID[j] + " " + qResponse[j]);
                                    if (quorumResponse[index] > minQuorum(quorumSize)) {
                                        qOut[j].println("null");
                                        IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                    } else {

                                        JSONObject quorum_signs = new JSONObject(qResponse[j]);
                                        String quorumsPrivateShareSign = quorum_signs.getString("privateShareSign");
                                        String quorumsPrivateKeySign = quorum_signs.getString("privateKeySign");

                                        String didHash = getValues(DATA_PATH + "DataTable.json", "didHash", "peerid",quorumID[j]);
                                        InitiatorConsensusLogger.debug("DID obtained for this quorum member : "+ didHash);

                                        String QuorumPublicKeyIpfsHash = getPubKeyIpfsHash_DIDserver(didHash,2); //get public key ipfs hash of the quorum member.
                                        InitiatorConsensusLogger.debug("Quorum's pub key ipfs hash : "+QuorumPublicKeyIpfsHash);

                                        String quorumPubKeyStr= IPFSNetwork.get(QuorumPublicKeyIpfsHash, ipfs); // get quorum member's public key from ipfs.

                                        String pubKeyAlgo = publicKeyAlgStr(quorumPubKeyStr);
                
                                        
                                        JSONObject detailsToVerify = new JSONObject();
                                        detailsToVerify.put("did", didHash);
                                        detailsToVerify.put("hash", hash);
                                        detailsToVerify.put("signature", quorumsPrivateShareSign);
                                        
                                        FileWriter payloadfile = new FileWriter(WALLET_DATA_PATH.concat("/detailsToVerify").concat(".json"));
                                        payloadfile.write(detailsToVerify.toString());
                                        payloadfile.close();
                                        
                                        
                                        InitiatorConsensusLogger.debug("Hash to check "+detailsToVerify.toString());
                                        InitiatorConsensusLogger.debug(" ");
                                        
                                        if ((verifySignature(quorumsPrivateShareSign,getPubKeyFromStr(quorumPubKeyStr,pubKeyAlgo),quorumsPrivateKeySign,pubKeyAlgo))){ 

                                            InitiatorConsensusLogger.debug("Private key sign verified of the quorum member."+ quorumID[j]);

                                            if ((Authenticate.verifySignature(detailsToVerify.toString()))) {
                                                InitiatorConsensusLogger
                                                        .debug(role + " node authenticated at index " + index);
                                                boolean voteStatus = voteNCount(index, quorumSize);
                                                if (quorumResponse[index] <= minQuorum(quorumSize) && voteStatus) {
                                                    InitiatorConsensusLogger
                                                            .debug("waiting for  " + quorumSize + " +signs " + role);
                                                    if (role.equals("alpha")) {
                                                        InitiatorConsensusLogger
                                                                .debug("Picking Quorum for Staking " + quorumID[j]);
                                                        signedAphaQuorumArray.put(quorumID[j]);
                                                    }
                                                    while (quorumResponse[index] < minQuorum(quorumSize)) {
                                                    }
                                                    InitiatorConsensusLogger
                                                            .debug("between Q1- to Q" + quorumSize + " for index " + index);
                                                    quorumSign(didHash, hash, quorumsPrivateShareSign, quorumsPrivateKeySign, index, quorumSize, alphaSize);
                                                    quorumWithShares.add(quorumPeersObject.getString(j));
                                                    while (quorumSignature
                                                            .length() < (minQuorum(alphaSize) + 2 * minQuorum(7))) {
                                                    }
                                                    InitiatorConsensusLogger.debug("sending Qsign  of length "
                                                            + quorumSignature.length() + "at index " + index);
                                                    qOut[j].println(finalQuorumSignsArray.toString());
                                                    IPFSNetwork
                                                            .executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                                } else {
                                                    InitiatorConsensusLogger.debug("sending null for slow quorum ");
                                                    qOut[j].println("null");
                                                    IPFSNetwork
                                                            .executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                                }
                                                InitiatorConsensusLogger.debug("Quorum Count : " + quorumResponse
                                                        + "Signature count : " + quorumSignature.length());
                                       } 
                                    }else {
                                            InitiatorConsensusLogger.debug("Private key sign verification failed of the quorum member.");
                                            InitiatorConsensusLogger
                                                    .debug("node failed authentication with index " + index
                                                            + " with role " + role + " with did " + didHash
                                                            + " and data to verify "
                                                            + detailsToVerify);
                                            IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                                        }
                                    }
                                }
                            }
                        }
                    } catch (IOException | JSONException e) {
                        IPFSNetwork.executeIPFSCommands("ipfs p2p close -t /p2p/" + quorumID[j]);
                        InitiatorConsensusLogger.error("IOException Occurred");
                        e.printStackTrace();
                    }
                });
                quorumThreads[j].start();
            }

            while (quorumResponse[index] < minQuorum(quorumSize)
                    || quorumSignature.length() < (minQuorum(alphaSize))) {
            }
            repo(ipfs);
        } catch (JSONException e) {
            InitiatorConsensusLogger.error("JSON Exception Occurred", e);
            e.printStackTrace();
        }
        return quorumSignature;
    }
}
