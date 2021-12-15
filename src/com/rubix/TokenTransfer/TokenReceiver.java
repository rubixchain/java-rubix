package com.rubix.TokenTransfer;

import com.rubix.AuthenticateNode.Authenticate;
import com.rubix.AuthenticateNode.PropImage;
import com.rubix.LevelDb.DataBase;
import com.rubix.Resources.Functions;
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
import java.net.ServerSocket;
import java.net.Socket;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;

import static com.rubix.Resources.Functions.*;
import static com.rubix.Resources.IPFSNetwork.*;
import static com.rubix.LevelDb.DataBase.*;


public class TokenReceiver {
    public static Logger TokenReceiverLogger = Logger.getLogger(TokenReceiver.class);

    private static final JSONObject APIResponse = new JSONObject();
    private static IPFS ipfs = new IPFS("/ip4/127.0.0.1/tcp/" + IPFS_PORT);
    private static String SenWalletBin;

    /**
     * Receiver Node: To receive a valid token from an authentic sender
     * @return Transaction Details (JSONObject)
     * @throws IOException handles IO Exceptions
     * @throws JSONException handles JSON Exceptions
     */
    public static String receive() {
        pathSet();
        ServerSocket ss = null;
        Socket sk = null;
        String senderPeerID = null;

        try {

            int quorumSignVerifyCount = 0;
            JSONObject quorumSignatures = null;

            ArrayList<String> quorumDID = new ArrayList<>();
            PropertyConfigurator.configure(LOGGER_PATH + "log4jWallet.properties");

            String receiverPeerID = getPeerID(DATA_PATH + "DID.json");

            String receiverDidIpfsHash = getValues(DATA_PATH + "DataTable.json", "didHash", "peerid", receiverPeerID);

            listen(receiverPeerID, RECEIVER_PORT);
            ss = new ServerSocket(RECEIVER_PORT);
            TokenReceiverLogger.debug("Listening on " + RECEIVER_PORT + " with app name " + receiverPeerID);

            sk = ss.accept();
            BufferedReader input = new BufferedReader(new InputStreamReader(sk.getInputStream()));
            PrintStream output = new PrintStream(sk.getOutputStream());
            long startTime = System.currentTimeMillis();

            senderPeerID = input.readLine();
            String senderDidIpfsHash = getValues(DATA_PATH + "DataTable.json", "didHash", "peerid", senderPeerID);
            String senderWidIpfsHash = getValues(DATA_PATH + "DataTable.json", "walletHash", "peerid", senderPeerID);

            if (!(senderDidIpfsHash.contains("Qm") && senderWidIpfsHash.contains("Qm"))) {
                output.println("420");
                APIResponse.put("did", senderDidIpfsHash);
                APIResponse.put("tid", "null");
                APIResponse.put("status", "Failed");
                APIResponse.put("message", "Sender details not available in network , please sync");
                TokenReceiverLogger.info("Sender details not available in datatable");
                /* executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);*/

                output.close();
                input.close();
                sk.close();
                ss.close();
                return APIResponse.toString();
            }


            nodeData(senderDidIpfsHash, senderWidIpfsHash, ipfs);
            File senderDIDFile = new File(DATA_PATH + senderDidIpfsHash + "/DID.png");
            if (!senderDIDFile.exists()) {
                output.println("420");
                APIResponse.put("did", senderDidIpfsHash);
                APIResponse.put("tid", "null");
                APIResponse.put("status", "Failed");
                APIResponse.put("message", "Sender details not available");
                TokenReceiverLogger.info("Sender details not available");
                /* executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);*/

                output.close();
                input.close();
                sk.close();
                ss.close();
                return APIResponse.toString();
            }
            TokenReceiverLogger.debug("Sender details authenticated");
            output.println("200");

            String data = input.readLine();
            TokenReceiverLogger.debug("Token details received: " + data);
            JSONArray TokenDetailsArray = new JSONArray(data);
            JSONArray tokens = TokenDetailsArray.getJSONObject(0).getJSONArray("token");
            JSONArray tokenChains = TokenDetailsArray.getJSONObject(0).getJSONArray("tokenChain");
            String getCIDipfsHash = TokenDetailsArray.getJSONObject(1).getString("ipfsHash");
            TokenReceiverLogger.debug("Checking providers for: " + getCIDipfsHash);
            ArrayList dhtOwnersList = dhtOwnerCheck(getCIDipfsHash);
            TokenReceiverLogger.debug("Providers: " + dhtOwnersList);
            TokenReceiverLogger.debug("IPFS get of consensusFile: " + getCIDipfsHash);
            String consensusID = get(getCIDipfsHash, ipfs);


            int tokenCount = tokens.length();

            String senderToken = TokenDetailsArray.getJSONObject(0).toString();

            String consensusIdCompare = calculateHash(senderToken, "SHA3-256");
//            writeToFile(LOGGER_PATH + "consensusID", consensusID, false);
//            String consensusIDIPFSHash = IPFSNetwork.addHashOnly(LOGGER_PATH + "consensusID", ipfs);
//            deleteFile(LOGGER_PATH + "consensusID");



            //Check IPFS get for all Tokens
            int ipfsGetFlag = 0;
            ArrayList<String> allTokenContent = new ArrayList<>();
            ArrayList<String> allTokenChainContent = new ArrayList<>();
            for (int i = 0; i < tokenCount; i++) {
                String TokenChainContent = get(tokenChains.getString(i), ipfs);
                allTokenChainContent.add(TokenChainContent);
                String TokenContent = get(tokens.getString(i), ipfs);
                allTokenContent.add(TokenContent);
                ipfsGetFlag++;
            }
            if(!consensusID.equals(consensusIdCompare)){
                String errorMessage = "Consensus ID not unique: Hashes do not match - " + "Sent " +  consensusID + " Recalculated " + consensusIdCompare;
                output.println("420");
                APIResponse.put("did", senderDidIpfsHash);
                APIResponse.put("tid", "null");
                APIResponse.put("status", "Failed");
                APIResponse.put("message", errorMessage);
                TokenReceiverLogger.debug(errorMessage);
                executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
                output.close();
                input.close();
                sk.close();
                ss.close();
                return APIResponse.toString();
            }
            else if(!(dhtOwnersList.size() == 1 && dhtOwnersList.contains(senderPeerID))){
                String errorMessage = "Consensus ID not unique: " + dhtOwnersList.size() + " owns the hash " + dhtOwnersList;
                output.println("421");
                APIResponse.put("did", senderDidIpfsHash);
                APIResponse.put("tid", "null");
                APIResponse.put("status", "Failed");
                APIResponse.put("message", errorMessage);
                TokenReceiverLogger.debug(errorMessage);
                executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
                output.close();
                input.close();
                sk.close();
                ss.close();
                return APIResponse.toString();
            }
            else if(!(ipfsGetFlag == tokenCount)){
                String errorMessage = "Tokens not verified";
                output.println("422");
                APIResponse.put("did", senderDidIpfsHash);
                APIResponse.put("tid", "null");
                APIResponse.put("status", "Failed");
                APIResponse.put("message", errorMessage);
                TokenReceiverLogger.debug(errorMessage);
                executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
                output.close();
                input.close();
                sk.close();
                ss.close();
                return APIResponse.toString();
            }

            repo(ipfs);
            output.println("200");


            String senderDetails = input.readLine();


            JSONObject SenderDetails = new JSONObject(senderDetails);
            String senderSignature = SenderDetails.getString("sign");
            String tid = SenderDetails.getString("tid");
            String comment = SenderDetails.getString("comment");
            String Status = SenderDetails.getString("status");
            String QuorumDetails = SenderDetails.getString("quorumsign");

            BufferedImage senderWidImage = ImageIO.read(new File(DATA_PATH + senderDidIpfsHash + "/PublicShare.png"));
            SenWalletBin = PropImage.img2bin(senderWidImage);

            // String Status = input.readLine();

            TokenReceiverLogger.debug("Consensus Status:  " + Status);

            if (!Status.equals("Consensus Failed")) {
                boolean yesQuorum = false;
                if (Status.equals("Consensus Reached")) {
//                String QuorumDetails = input.readLine();

//                    TokenReceiverLogger.debug("Quorum Signatures: " + QuorumDetails);
                    quorumSignatures = new JSONObject(QuorumDetails);
                    int alphaSize = quorumSignatures.length() - 10;

//                    String selectQuorumHash = calculateHash(senderToken, "SHA3-256");

                    String verifyQuorumHash = calculateHash(getCIDipfsHash.concat(receiverDidIpfsHash), "SHA3-256");
//                    TokenReceiverLogger.debug("Quorum Hash on Receiver Side " + verifyQuorumHash);
//                    TokenReceiverLogger.debug("Quorum Signatures length : " + quorumSignatures.length());

                    Iterator<String> keys = quorumSignatures.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        quorumDID.add(key);
                    }

                    for (String quorumDidIpfsHash : quorumDID) {
                        String quorumWidIpfsHash = getValues(DATA_PATH + "DataTable.json", "walletHash", "didHash", quorumDidIpfsHash);

                        File quorumDataFolder = new File(DATA_PATH + quorumDidIpfsHash + "/");
                        if (!quorumDataFolder.exists()) {
                            quorumDataFolder.mkdirs();
                            IPFSNetwork.getImage(quorumDidIpfsHash, ipfs, DATA_PATH + quorumDidIpfsHash + "/DID.png");
                            IPFSNetwork.getImage(quorumWidIpfsHash, ipfs, DATA_PATH + quorumDidIpfsHash + "/PublicShare.png");
//                            TokenReceiverLogger.debug("Quorum Data " + quorumDidIpfsHash + " Added");
                        }
                    }

                    for (int i = 0; i < quorumSignatures.length(); i++) {

                        JSONObject detailsForVerify = new JSONObject();
                        detailsForVerify.put("did", quorumDID.get(i));
                        detailsForVerify.put("hash", verifyQuorumHash);
                        detailsForVerify.put("signature", quorumSignatures.getString(quorumDID.get(i)));
                        boolean val = Authenticate.verifySignature(detailsForVerify.toString());
                        if (val)
                            quorumSignVerifyCount++;
                    }
                    TokenReceiverLogger.debug("Verified Quorum Count " + quorumSignVerifyCount);
                    yesQuorum = quorumSignVerifyCount >= quorumSignatures.length();
                }
                ArrayList<String> allTokensChainsPushed = new ArrayList<>();
                for (int i = 0; i < tokenCount; i++)
                    allTokensChainsPushed.add(tokenChains.getString(i));

                String hash = calculateHash(tokens.toString() + allTokensChainsPushed.toString() + receiverDidIpfsHash + comment, "SHA3-256");

                JSONObject detailsForVerify = new JSONObject();
                detailsForVerify.put("did", senderDidIpfsHash);
                detailsForVerify.put("hash", hash);
                detailsForVerify.put("signature", senderSignature);

                boolean yesSender = Authenticate.verifySignature(detailsForVerify.toString());
//                TokenReceiverLogger.debug("Sender auth hash " + hash);
//                TokenReceiverLogger.debug("Quorum Auth : " + yesQuorum + "Sender Auth : " + yesSender);
                if (!(yesSender && yesQuorum)) {
                    output.println("420");
                    APIResponse.put("did", senderDidIpfsHash);
                    APIResponse.put("tid", tid);
                    APIResponse.put("status", "Failed");
                    APIResponse.put("message", "Sender / Quorum not verified");
                    TokenReceiverLogger.info("Sender / Quorum not verified");
                    executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
                    output.close();
                    input.close();
                    sk.close();
                    ss.close();
                    return APIResponse.toString();
                }

                repo(ipfs);
                TokenReceiverLogger.debug("Sender and Quorum Verified");
                output.println("200");

                String readServer = input.readLine();
                if (readServer.equals("Unpinned")) {
                    int count = 0;
                    for (int i = 0; i < tokenCount; i++) {
                        FileWriter fileWriter;
                        fileWriter = new FileWriter(TOKENS_PATH + tokens.getString(i));
                        fileWriter.write(allTokenContent.get(i));
                        fileWriter.close();
                        add(TOKENS_PATH + tokens.getString(i), ipfs);
                        pin(tokens.getString(i), ipfs);
                        count++;

                    }

                    if (count == tokenCount) {
                        TokenReceiverLogger.debug("Pinned All Tokens");
                        output.println("Successfully Pinned");

                        String essentialShare = input.readLine();
                        long endTime = System.currentTimeMillis();

                        for (int i = 0; i < tokenCount; i++) {

                            ArrayList<String> groupTokens = new ArrayList<>();
                            for (int k = 0; k < tokenCount; k++) {
                                if (!tokens.getString(i).equals(tokens.getString(k)))
                                    groupTokens.add(tokens.getString(k));
                            }

                            JSONArray arrToken = new JSONArray();
                            JSONObject objectToken = new JSONObject();
                            objectToken.put("tokenHash", tokens.getString(i));
                            arrToken.put(objectToken);
                            JSONArray arr1 = new JSONArray(allTokenChainContent.get(i));
                            JSONObject obj2 = new JSONObject();
                            obj2.put("senderSign", senderSignature);
                            obj2.put("sender", senderDidIpfsHash);
                            obj2.put("group", groupTokens);
                            obj2.put("comment", comment);
                            obj2.put("tid", tid);
                            arr1.put(obj2);
                            writeToFile(TOKENCHAIN_PATH + tokens.getString(i) + ".json", arr1.toString(), false);
                        }

                        JSONObject transactionRecord = new JSONObject();
                        //jsonobject to store essentialshare 
                        JSONObject essentialShareRecord= new JSONObject();
                        transactionRecord.put("role", "Receiver");
                        transactionRecord.put("tokens", tokens);
                        transactionRecord.put("txn", tid);
                        transactionRecord.put("quorumList", quorumSignatures.keys());
                        transactionRecord.put("senderDID", senderDidIpfsHash);
                        transactionRecord.put("receiverDID", receiverDidIpfsHash);
                        transactionRecord.put("Date", getCurrentUtcTime());
                        transactionRecord.put("totalTime", (endTime - startTime));
                        transactionRecord.put("comment", comment);
                        
                        //transactionRecord.put("essentialShare", essentialShare);
                        essentialShareRecord.put("essentialShare", essentialShare);

                        /* JSONArray transactionHistoryEntry = new JSONArray();
                        transactionHistoryEntry.put(transactionRecord);
                        updateJSON("add", WALLET_DATA_PATH + "TransactionHistory.json", transactionHistoryEntry.toString()); */
                        DataBase.putDataTransactionHistory(transactionRecord.getString("txn").toString(), transactionRecord.toString());
                        DataBase.putDataEssentialShare(transactionRecord.getString("txn"), essentialShareRecord.toString());

                        TokenReceiverLogger.info("Transaction ID: " + tid + "Transaction Successful");
                        output.println("Send Response");
                        APIResponse.put("did", senderDidIpfsHash);
                        APIResponse.put("tid", tid);
                        APIResponse.put("status", "Success");
                        APIResponse.put("tokens", tokens);
                        APIResponse.put("comment", comment);
                        APIResponse.put("message", "Transaction Successful");
//                        TokenReceiverLogger.info(" Transaction Successful");
                        executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
                        output.close();
                        input.close();
                        sk.close();
                        ss.close();
                        return APIResponse.toString();

                    }
                    output.println("count mistmatch");
                    APIResponse.put("did", senderDidIpfsHash);
                    APIResponse.put("tid", "null");
                    APIResponse.put("status", "Failed");
                    APIResponse.put("message", "count mismacth");
                    TokenReceiverLogger.info(" Transaction failed");
                    executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
                    output.close();
                    input.close();
                    sk.close();
                    ss.close();
                    return APIResponse.toString();

                }

                APIResponse.put("did", senderDidIpfsHash);
                APIResponse.put("tid", "null");
                APIResponse.put("status", "Failed");
                APIResponse.put("message", "Failed to unpin");
                TokenReceiverLogger.info(" Transaction failed");
                executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
                output.close();
                input.close();
                sk.close();
                ss.close();
                return APIResponse.toString();

            }
            APIResponse.put("did", senderDidIpfsHash);
            APIResponse.put("tid", "null");
            APIResponse.put("status", "Failed");
            APIResponse.put("message", "Consensus failed at Sender side");
            TokenReceiverLogger.info(" Transaction failed");
            executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
            output.close();
            input.close();
            sk.close();
            ss.close();
            return APIResponse.toString();

        }
        catch (Exception e) {
            executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
            TokenReceiverLogger.error("Exception Occurred", e);
            return APIResponse.toString();
        }
        finally{
            try {
                ss.close();
                sk.close();
                executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
            } catch (Exception e) {
                executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
                TokenReceiverLogger.error("Exception Occurred", e);
            }

        }
    }
}
