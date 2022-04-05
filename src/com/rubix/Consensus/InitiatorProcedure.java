package com.rubix.Consensus;

import com.rubix.Constants.ConsensusConstants;
import com.rubix.SplitandStore.SeperateShares;
import com.rubix.SplitandStore.Split;
import io.ipfs.api.IPFS;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import static com.rubix.Resources.Functions.*;

public class InitiatorProcedure {
    public static String essential;
    public static String senderSignQ;
    public static JSONObject payload = new JSONObject();
    public static JSONObject alphaReply, betaReply, gammaReply;

    public static Logger InitiatorProcedureLogger = Logger.getLogger(InitiatorProcedure.class);

    /**
     * This function sets up the initials before the consensus
     * 
     * @param data Data required for hashing and signing
     * @param ipfs IPFS instance
     * @param PORT port for forwarding to quorum
     */
    public static void consensusSetUp(String data, IPFS ipfs, int PORT, int alphaSize, String operation)
            throws JSONException {
        PropertyConfigurator.configure(LOGGER_PATH + "log4jWallet.properties");

        JSONObject dataObject = new JSONObject(data);
        String tid = dataObject.getString("tid");
        String message = dataObject.getString("message");
        String receiverDidIpfs = dataObject.getString("receiverDidIpfs");
        String pvt = dataObject.getString("pvt");
        String senderDidIpfs = dataObject.getString("senderDidIpfs");
        String token = dataObject.getString("token");
        JSONArray alphaList = dataObject.getJSONArray("alphaList");
        JSONArray betaList = dataObject.getJSONArray("betaList");
        JSONArray gammaList = dataObject.getJSONArray("gammaList");

        String authSenderByQuorumHash = calculateHash(message, "SHA3-256");

        String authQuorumHash = calculateHash(authSenderByQuorumHash.concat(receiverDidIpfs), "SHA3-256");

        try {
            payload.put("sender", senderDidIpfs);
            payload.put("token", token);
            payload.put("receiver", receiverDidIpfs);
            payload.put("tid", tid);
        } catch (JSONException e) {
            InitiatorProcedureLogger.error("JSON Exception occurred", e);
            e.printStackTrace();
        }

        Split.split(payload.toString());

        int[][] shares = Split.get135Shares();
        InitiatorProcedureLogger.debug("Payload Split Success");
        essential = SeperateShares.getShare(shares, payload.toString().length(), 0);
        String Q1Share = SeperateShares.getShare(shares, payload.toString().length(), 1);
        String Q2Share = SeperateShares.getShare(shares, payload.toString().length(), 2);
        String Q3Share = SeperateShares.getShare(shares, payload.toString().length(), 3);
        String Q4Share = SeperateShares.getShare(shares, payload.toString().length(), 4);
        JSONObject data1 = new JSONObject();
        JSONObject data2 = new JSONObject();
        try {
            senderSignQ = getSignFromShares(pvt, authSenderByQuorumHash);
            data1.put("sign", senderSignQ);
            data1.put("senderDID", senderDidIpfs);
            data1.put(ConsensusConstants.TRANSACTION_ID, tid);
            data1.put(ConsensusConstants.HASH, authSenderByQuorumHash);
            data1.put(ConsensusConstants.RECEIVERID, receiverDidIpfs);

            data2.put("Share1", Q1Share);
            data2.put("Share2", Q2Share);
            data2.put("Share3", Q3Share);
            data2.put("Share4", Q4Share);
        } catch (JSONException | IOException e) {
            InitiatorProcedureLogger.error("JSON Exception occurred", e);
            e.printStackTrace();
        }

        JSONArray detailsForQuorum = new JSONArray();
        detailsForQuorum.put(data1);
        detailsForQuorum.put(data2);

        InitiatorProcedureLogger.debug("Invoking Consensus");

        JSONObject dataSend = new JSONObject();
        dataSend.put("hash", authQuorumHash);
        dataSend.put("details", detailsForQuorum);

        if (operation.equals("new-credits-mining")) {
            JSONObject qstDetails = dataObject.getJSONObject("qstDetails");
            dataSend.put("qstDetails", qstDetails);
        }

        if (operation.equals("NFT")) {
            JSONObject nftdetails = new JSONObject();
            nftdetails.put("sellerPubKeyIpfsHash", dataObject.getString("sellerPubKeyIpfsHash"));
            nftdetails.put("saleContractIpfsHash", dataObject.getString("saleContractIpfsHash"));
            nftdetails.put("nftTokenDetails", dataObject.getJSONObject("nftTokenDetails"));
            nftdetails.put("rbtTokenDetails", dataObject.getJSONObject("rbtTokenDetails"));
            // nftdetails.put("sellerPvtKeySign", dataObject.getString("sellerPvtKeySign"));
            nftdetails.put("tokenAmount", dataObject.getDouble("tokenAmount"));
            String authNftSenderByQuorumHash = calculateHash(dataObject.getJSONObject("nftTokenDetails").toString(),
                    "SHA3-256");
            nftdetails.put("nftHash", authNftSenderByQuorumHash);
            nftdetails.put("nftBuyerDid", senderDidIpfs);

            dataSend.put("nftDetails", nftdetails);

        }

        Thread alphaThread = new Thread(() -> {
            try {
                alphaReply = InitiatorConsensus.start(dataSend.toString(), ipfs, PORT, 0, "alpha", alphaList, alphaSize,
                        alphaSize, operation);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        });

        Thread betaThread = new Thread(() -> {
            try {
                betaReply = InitiatorConsensus.start(dataSend.toString(), ipfs, PORT + 100, 1, "beta", betaList,
                        alphaSize, 7, operation);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        });

        Thread gammaThread = new Thread(() -> {
            try {
                gammaReply = InitiatorConsensus.start(dataSend.toString(), ipfs, PORT + 107, 2, "gamma", gammaList,
                        alphaSize, 7, operation);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        });

        InitiatorConsensus.quorumSignature = new JSONObject();
        InitiatorConsensus.finalQuorumSignsArray = new JSONArray();
        alphaThread.start();
        betaThread.start();
        gammaThread.start();

        if (operation.equals("NFT")) {
            while ((InitiatorConsensus.quorumSignature.length() < ((minQuorum(alphaSize) + 2 * minQuorum(7))))
                    && (InitiatorConsensus.nftQuorumSignature.length() < ((minQuorum(alphaSize) + 2 * minQuorum(7))))) {
            }
            InitiatorProcedureLogger.debug(
                    "ABG NFT Consensus completed with length for NFT :" + InitiatorConsensus.nftQuorumSignature.length()
                            + " RBT " + InitiatorConsensus.quorumSignature.length());
        } else {
            while (InitiatorConsensus.quorumSignature.length() < (minQuorum(alphaSize) + 2 * minQuorum(7))) {
            }
            InitiatorProcedureLogger
                    .debug("ABG Consensus completed with length " + InitiatorConsensus.quorumSignature.length());
        }

        InitiatorProcedureLogger.debug("InitiatorProcedure.java class finished");
    }
   
}
