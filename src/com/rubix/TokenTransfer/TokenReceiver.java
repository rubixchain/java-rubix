package com.rubix.TokenTransfer;

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
import java.net.ServerSocket;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;

import static com.rubix.Resources.Functions.*;
import static com.rubix.Resources.IPFSNetwork.*;


public class TokenReceiver {
    public static Logger TokenReceiverLogger = Logger.getLogger(TokenReceiver.class);
    private static ArrayList<String> quorumPEER;
    private static final JSONObject APIResponse = new JSONObject();
    private static IPFS ipfs = new IPFS("/ip4/127.0.0.1/tcp/" + IPFS_PORT);
    private static String SenWalletBin;

    /**
     * Receiver Node: To receive a valid token from an authentic sender
     * @return Transaction Details (JSONObject)
     * @throws IOException handles IO Exceptions
     * @throws JSONException handles JSON Exceptions
     * @throws NoSuchAlgorithmException handles No Such Algorithm Exceptions
     */
    public static JSONObject receive() throws IOException, JSONException, NoSuchAlgorithmException {
        pathSet();

        int quorumSignVerifyCount = 0;
        PropertyConfigurator.configure(LOGGER_PATH + "log4jWallet.properties");


        DateFormat formatter = new SimpleDateFormat("yyyy/MM/dd");
        Date date = new Date();
        LocalDate currentTime = LocalDate.parse(formatter.format(date).replace("/", "-"));

        String receiverPeerID = getPeerID(DATA_PATH + "DID.json");

        String receiverDidIpfsHash = getValues(DATA_PATH + "DataTable.json", "didHash", "peerid", receiverPeerID);
        BufferedImage receiverWidImage = ImageIO.read(new File(DATA_PATH + receiverDidIpfsHash + "/PublicShare.png"));
        String receiverWidBin = PropImage.img2bin(receiverWidImage);

        listen(receiverPeerID, RECEIVER_PORT);
        ServerSocket ss = new ServerSocket(RECEIVER_PORT);
        TokenReceiverLogger.debug("Listening on " + RECEIVER_PORT + " with app name " + receiverPeerID);

        Socket sk = ss.accept();
        BufferedReader input = new BufferedReader(new InputStreamReader(sk.getInputStream()));
        PrintStream output = new PrintStream(sk.getOutputStream());
        long startTime = System.currentTimeMillis();

        String senderPeerID = input.readLine();
        String senderDidIpfsHash = getValues(DATA_PATH + "DataTable.json", "didHash", "peerid", senderPeerID);
        String senderWidIpfsHash = getValues(DATA_PATH + "DataTable.json", "walletHash", "peerid", senderPeerID);

        nodeData(senderDidIpfsHash, senderWidIpfsHash, ipfs);
        File senderDIDFile = new File(DATA_PATH + senderDidIpfsHash + "/DID.png");
        if (!senderDIDFile.exists()) {
            output.println("420");
            APIResponse.put("did", receiverDidIpfsHash);
            APIResponse.put("tid", "null");
            APIResponse.put("status", "Failed");
            APIResponse.put("message", "Sender details not available");
            TokenReceiverLogger.info("Sender details not available");
            executeIPFSCommands(" ipfs p2p close -t /ipfs/" + senderPeerID);
            sk.close();
            input.close();
            output.close();
            ss.close();
            return APIResponse;
        } else
            output.println("200");

        String data = input.readLine();
        JSONObject TokenDetails = new JSONObject(data);
        JSONArray tokens =  TokenDetails.getJSONArray("token");
        JSONArray tokenChains =  TokenDetails.getJSONArray("tokenChain");
        JSONArray tokenHeader =  TokenDetails.getJSONArray("tokenHeader");
        int tokenCount = tokens.length();

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

        repo(ipfs);
        if (!(ipfsGetFlag == tokenCount)) {
            output.println("420");
            APIResponse.put("did", receiverDidIpfsHash);
            APIResponse.put("tid", "null");
            APIResponse.put("status", "Failed");
            APIResponse.put("message", "Tokens not verified");
            TokenReceiverLogger.info("Tokens not verified");
            executeIPFSCommands(" ipfs p2p close -t /ipfs/" + senderPeerID);
            sk.close();
            input.close();
            output.close();
            ss.close();
            return APIResponse;
        } else
            output.println("200");

        String senderDetails = input.readLine();

        JSONObject SenderDetails = new JSONObject(senderDetails);
        String senderSignature = SenderDetails.getString("sign");
        String tid = SenderDetails.getString("tid");
        String comment = SenderDetails.getString("comment");


        BufferedImage senderWidImage = ImageIO.read(new File(DATA_PATH + senderDidIpfsHash + "/PublicShare.png"));
        SenWalletBin = PropImage.img2bin(senderWidImage);

        String Status = input.readLine();
        TokenReceiverLogger.debug("Consensus Status:  " + Status);

        if (!Status.equals("Consensus failed")) {
            boolean yesQuorum;
            if (Status.equals("Consensus Reached")) {
                String QuorumDetails = input.readLine();

                TokenReceiverLogger.debug("Quorum Signatures: " + QuorumDetails);
                JSONObject quorumSignatures = new JSONObject(QuorumDetails);
                String selectQuorumHash = calculateHash(SenWalletBin + tokens, "SHA3-256");

                String verifyQuorumHash = calculateHash(selectQuorumHash.concat(receiverDidIpfsHash), "SHA3-256");
                TokenReceiverLogger.debug("Quorum Hash on Receiver Side " + verifyQuorumHash);
                TokenReceiverLogger.debug("Quorum Signatures length : " + quorumSignatures.length());

                Iterator<String> keys = quorumSignatures.keys();
                ArrayList<String> quorumDID = new ArrayList<>();
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
                        TokenReceiverLogger.debug("Quorum Data " + quorumDidIpfsHash + " Added");
                    } else
                        TokenReceiverLogger.debug("Quorum Data " + quorumDidIpfsHash + " Available");
                }

                for (int i = 0; i < quorumSignatures.length(); i++) {
                    String widQuorumHash = getValues(DATA_PATH + "DataTable.json", "walletHash", "didHash", quorumDID.get(i));
                    BufferedImage didQuorumImage = ImageIO.read(new File(DATA_PATH + quorumDID.get(i) + "/DID.png"));
                    BufferedImage widQuorumImage = ImageIO.read(new File(DATA_PATH + quorumDID.get(i) + "/PublicShare.png"));
                    String didQuorumBin = PropImage.img2bin(didQuorumImage);
                    String widQuorumBin = PropImage.img2bin(widQuorumImage);
                    JSONObject detailsForVerify = new JSONObject();
                    detailsForVerify.put("did", quorumDID.get(i));
                    detailsForVerify.put("hash", verifyQuorumHash);
                    detailsForVerify.put("signature",  quorumSignatures.getString(quorumDID.get(i)));
                    boolean val = Authenticate.verifySignature(detailsForVerify.toString());
                    if (val)
                        quorumSignVerifyCount++;
                }
                TokenReceiverLogger.debug("Verified Quorum Count " + quorumSignVerifyCount);
                yesQuorum = quorumSignVerifyCount >= minQuorum();
            } else
                yesQuorum = true;

            ArrayList<String> allTokensChainsPushed = new ArrayList<>();
            for (int i = 0; i < tokenCount; i++)
                allTokensChainsPushed.add(tokenChains.getString(i));

            String hash = calculateHash(tokens.toString() + allTokensChainsPushed.toString() + receiverWidBin + comment, "SHA3-256");
            String SenDIDHash = getValues(DATA_PATH + "DataTable.json", "didHash", "peerid", senderPeerID);
            BufferedImage senderDIDImage = ImageIO.read(new File(DATA_PATH + SenDIDHash + "/DID.png"));
            String senderDIDBin = PropImage.img2bin(senderDIDImage);

            JSONObject detailsForVerify = new JSONObject();
            detailsForVerify.put("did", senderDidIpfsHash);
            detailsForVerify.put("hash", hash);
            detailsForVerify.put("signature", senderSignature);

            boolean yesSender = Authenticate.verifySignature(detailsForVerify.toString());
            TokenReceiverLogger.debug("Sender auth hash " + hash);
            TokenReceiverLogger.debug("Quorum Auth : " + yesQuorum + "Sender Auth : " + yesSender);
            if (!(yesSender && yesQuorum)) {
                output.println("420");
                APIResponse.put("did", receiverDidIpfsHash);
                APIResponse.put("tid", tid);
                APIResponse.put("status", "Failed");
                APIResponse.put("message", "Sender / Quorum not verified");
                TokenReceiverLogger.info("Sender / Quorum not verified");
                executeIPFSCommands(" ipfs p2p close -t /ipfs/" + senderPeerID);
                sk.close();
                input.close();
                output.close();
                ss.close();
                return APIResponse;
            } else {
                repo(ipfs);
                TokenReceiverLogger.debug("Sender and Quorum Verified");
                output.println("200");
            }


            String readServer = input.readLine();
            if (readServer.equals("Unpinned")) {

                for (int i = 0; i < tokenCount; i++) {
                    FileWriter fileWriter;
                    fileWriter = new FileWriter(TOKENS_PATH + tokens.getString(i));
                    fileWriter.write(allTokenContent.get(i));
                    fileWriter.close();

                    add(TOKENS_PATH + tokens.getString(i), ipfs);
                    pin(tokens.getString(i), ipfs);
                }

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
                    obj2.put("peer-id", senderPeerID);
                    obj2.put("group", groupTokens);
                    obj2.put("comment", comment);
                    arr1.put(obj2);
                    writeToFile(TOKENCHAIN_PATH + tokens.getString(i) + ".json", arr1.toString(), false);
                }

                JSONObject transactionRecord = new JSONObject();
                transactionRecord.put("role", "Receiver");
                transactionRecord.put("tokens", tokens);
                transactionRecord.put("txn", tid);
                transactionRecord.put("quorumList", quorumPEER);
                transactionRecord.put("senderDID", senderDidIpfsHash);
                transactionRecord.put("receiverDID", receiverDidIpfsHash);
                transactionRecord.put("Date", currentTime);
                transactionRecord.put("totalTime", (endTime - startTime));
                transactionRecord.put("comment", comment);
                transactionRecord.put("essentialShare", essentialShare);

                JSONArray transactionHistoryEntry = new JSONArray();
                transactionHistoryEntry.put(transactionRecord);
                updateJSON("add", WALLET_DATA_PATH + "TransactionHistory.json", transactionHistoryEntry.toString());

                TokenReceiverLogger.info("Transaction ID: " + tid + "Transaction Successful");
                output.println("Send Response");
            }
        }
        APIResponse.put("did", senderDidIpfsHash);
        APIResponse.put("tid", tid);
        APIResponse.put("status", "Success");
        APIResponse.put("tokens", tokens);
        APIResponse.put("tokenHeader", tokenHeader);
        APIResponse.put("comment", comment);
        APIResponse.put("message", "Transaction Successful");
        TokenReceiverLogger.info(" Transaction Successful");
        executeIPFSCommands(" ipfs p2p close -t /ipfs/" + senderPeerID);
        sk.close();
        input.close();
        output.close();
        ss.close();
        return APIResponse;
    }
}