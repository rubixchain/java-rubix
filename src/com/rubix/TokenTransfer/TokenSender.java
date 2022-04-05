package com.rubix.TokenTransfer;

import com.rubix.Consensus.InitiatorConsensus;
import com.rubix.Consensus.InitiatorProcedure;
import com.rubix.Resources.Functions;
import com.rubix.Resources.IPFSNetwork;
import io.ipfs.api.IPFS;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static com.rubix.Resources.Functions.*;
import static com.rubix.Resources.IPFSNetwork.*;


public class TokenSender {
    private static final Logger TokenSenderLogger = Logger.getLogger(TokenSender.class);
    private static final Logger eventLogger = Logger.getLogger("eventLogger");

    public static BufferedReader serverInput;
    private static PrintStream output;
    private static BufferedReader input;
    private static Socket senderSocket;
    private static boolean senderMutex = false;

    /**
     * A sender node to transfer tokens
     *
     * @param data Details required for tokenTransfer
     * @param ipfs IPFS instance
     * @param port Sender port for communication
     * @return Transaction Details (JSONObject)
     * @throws IOException              handles IO Exceptions
     * @throws JSONException            handles JSON Exceptions
     * @throws NoSuchAlgorithmException handles No Such Algorithm Exceptions
     */
    public static JSONObject Send(String data, IPFS ipfs, int port) throws Exception {
        repo(ipfs);
        JSONObject APIResponse = new JSONObject();
        PropertyConfigurator.configure(LOGGER_PATH + "log4jWallet.properties");

        String receiverPeerId;
        JSONObject detailsObject = new JSONObject(data);
        String receiverDidIpfsHash = detailsObject.getString("receiverDidIpfsHash");
        String pvt = detailsObject.getString("pvt");
        double requestedAmount = detailsObject.getDouble("amount");
        int type = detailsObject.getInt("type");
        String comment = detailsObject.getString("comment");
        APIResponse = new JSONObject();

        String senderPeerID = getPeerID(DATA_PATH + "DID.json");
        TokenSenderLogger.debug("sender peer id" + senderPeerID);
        String senderDidIpfsHash = getValues(DATA_PATH + "DataTable.json", "didHash", "peerid", senderPeerID);
        TokenSenderLogger.debug("sender did ipfs hash" + senderDidIpfsHash);

        if (senderMutex) {
            APIResponse.put("did", senderDidIpfsHash);
            APIResponse.put("tid", "null");
            APIResponse.put("status", "Failed");
            APIResponse.put("message", "Sender busy. Try again later");
            TokenSenderLogger.warn("Sender busy");
            return APIResponse;
        }

        senderMutex = true;

        String PART_TOKEN_CHAIN_PATH = TOKENCHAIN_PATH.concat("PARTS/");
        String PART_TOKEN_PATH = TOKENS_PATH.concat("PARTS/");
        File partFolder = new File(PART_TOKEN_PATH);
        if (!partFolder.exists())
            partFolder.mkdir();
        partFolder = new File(PART_TOKEN_CHAIN_PATH);
        if (!partFolder.exists())
            partFolder.mkdir();
        File partTokensFile = new File(PAYMENTS_PATH.concat("PartsToken.json"));
        if (!partTokensFile.exists()) {
            partTokensFile.createNewFile();
            writeToFile(partTokensFile.toString(), "[]", false);
        }

        int intPart = (int) requestedAmount, wholeAmount;
        TokenSenderLogger.debug("Requested Part: " +requestedAmount);
        TokenSenderLogger.debug("Int Part: " +intPart);
        String bankFile = readFile(PAYMENTS_PATH.concat("BNK00.json"));
        JSONArray bankArray = new JSONArray(bankFile);
        JSONArray wholeTokens = new JSONArray();
        if (intPart <= bankArray.length())
            wholeAmount = intPart;
        else
            wholeAmount = bankArray.length();

        for (int i = 0; i < wholeAmount; i++) {
            wholeTokens.put(bankArray.getJSONObject(i).getString("tokenHash"));
        }

        for(int i = 0; i < wholeTokens.length(); i++){
            String tokenRemove = wholeTokens.getString(i);
            for(int j = 0; j < bankArray.length(); j++){
                if(bankArray.getJSONObject(j).getString("tokenHash").equals(tokenRemove))
                    bankArray.remove(j);
            }
        }
        JSONArray wholeTokenChainHash = new JSONArray();
        JSONArray tokenPreviousSender = new JSONArray();
        for (int i = 0; i < wholeTokens.length(); i++) {
            File token = new File(TOKENS_PATH + wholeTokens.get(i));
            File tokenchain = new File(TOKENCHAIN_PATH + wholeTokens.get(i) + ".json");
            if (!(token.exists() && tokenchain.exists())) {
                TokenSenderLogger.info("Tokens Not Verified");
                senderMutex = false;
                APIResponse.put("did", senderDidIpfsHash);
                APIResponse.put("tid", "null");
                APIResponse.put("status", "Failed");
                APIResponse.put("message", "Invalid token(s)");
                return APIResponse;

            }
            String wholeTokenHash = add(TOKENS_PATH + wholeTokens.get(i), ipfs);
            pin(wholeTokenHash, ipfs);
            String tokenChainHash = add(TOKENCHAIN_PATH + wholeTokens.get(i) + ".json", ipfs);
            wholeTokenChainHash.put(tokenChainHash);


            String tokenChainFileContent = readFile(TOKENCHAIN_PATH + wholeTokens.get(i) + ".json");
            JSONArray tokenChainFileArray = new JSONArray(tokenChainFileContent);
            JSONArray previousSenderArray = new JSONArray();
            for (int j = 0; j < tokenChainFileArray.length(); j++) {
                String peerID = getValues(DATA_PATH + "DataTable.json", "peerid", "didHash", tokenChainFileArray.getJSONObject(j).getString("sender"));
                previousSenderArray.put(peerID);
            }

            JSONObject previousSenderObject = new JSONObject();
            previousSenderObject.put("token", wholeTokenHash);
            previousSenderObject.put("sender", previousSenderArray);
            tokenPreviousSender.put(previousSenderObject);
        }

        Double decimalAmount = requestedAmount - wholeAmount;
        decimalAmount = formatAmount(decimalAmount);

        TokenSenderLogger.debug("Decimal Part: " +decimalAmount);
        boolean newPart = false, oldNew = false;
        JSONObject amountLedger = new JSONObject();

        JSONArray partTokens = new JSONArray();
        JSONArray partTokenChainHash = new JSONArray();
        if (decimalAmount > 0.000D) {
            TokenSenderLogger.debug("Decimal Amount > 0.000D");
            String partFileContent = readFile(partTokensFile.toString());
            JSONArray partContentArray = new JSONArray(partFileContent);

            if (partContentArray.length() == 0) {
                newPart = true;
                TokenSenderLogger.debug("New token for parts");
                String chosenToken = bankArray.getJSONObject(0).getString("tokenHash");
                partTokens.put(chosenToken);
                amountLedger.put(chosenToken, formatAmount(decimalAmount));

            } else {
                Double counter = decimalAmount;
                JSONArray selectParts = new JSONArray(partFileContent);
                while (counter > 0.000D) {
                    counter = formatAmount(counter);
                    TokenSenderLogger.debug("Counter: " + formatAmount(counter) );
                    if(!(selectParts.length() == 0)) {
                        TokenSenderLogger.debug("Old Parts");
                        String currentPartToken = selectParts.getJSONObject(0).getString("tokenHash");
                        Double currentPartBalance = partTokenBalance(currentPartToken);
                        currentPartBalance = formatAmount(currentPartBalance);
                        if (counter >= currentPartBalance)
                            amountLedger.put(currentPartToken, formatAmount(currentPartBalance));
                        else
                            amountLedger.put(currentPartToken, formatAmount(counter));

                        partTokens.put(currentPartToken);
                        counter -= currentPartBalance;
                        selectParts.remove(0);
                    }else{
                        oldNew = true;
                        TokenSenderLogger.debug("Old Parts then new parts");
                        String chosenToken = bankArray.getJSONObject(0).getString("tokenHash");
                        partTokens.put(chosenToken);
                        amountLedger.put(chosenToken, formatAmount(counter));
                        File tokenFile = new File(TOKENS_PATH.concat(chosenToken));
                        tokenFile.renameTo(new File(PART_TOKEN_PATH.concat(chosenToken)));
                        File chainFile = new File(TOKENCHAIN_PATH.concat(chosenToken).concat(".json"));
                        chainFile.renameTo(new File(PART_TOKEN_CHAIN_PATH.concat(chosenToken).concat(".json")));


                        File shiftedFile = new File(PAYMENTS_PATH.concat("ShiftedTokens.json"));
                        if (!shiftedFile.exists()) {
                            shiftedFile.createNewFile();
                            JSONArray shiftedTokensArray = new JSONArray();
                            shiftedTokensArray.put(chosenToken);
                            writeToFile(PAYMENTS_PATH.concat("ShiftedTokens.json"), shiftedTokensArray.toString(), false);
                        } else {
                            String shiftedContent = readFile(PAYMENTS_PATH.concat("ShiftedTokens.json"));
                            JSONArray shiftedArray = new JSONArray(shiftedContent);
                            shiftedArray.put(chosenToken);
                            writeToFile(PAYMENTS_PATH.concat("ShiftedTokens.json"), shiftedArray.toString(), false);
                        }
                        counter = 0.000D;
                    }
                }
            }
        }
        String tokenChainPath = "", tokenPath = "";
        if(newPart) {
            tokenChainPath = TOKENCHAIN_PATH;
            tokenPath = TOKENS_PATH;
        }
        else{
            tokenChainPath = TOKENCHAIN_PATH.concat("PARTS/");
            tokenPath = TOKENS_PATH.concat("PARTS/");
        }

        TokenSenderLogger.debug("Tokenchain path: " + tokenChainPath);
        TokenSenderLogger.debug("Token path: " + tokenPath);
        for (int i = 0; i < partTokens.length(); i++) {
            File token = new File(tokenPath.concat(partTokens.getString(i)));
            File tokenchain = new File(tokenChainPath.concat(partTokens.getString(i)) + ".json");
            if (!(token.exists() && tokenchain.exists())) {
                if (!token.exists())
                    TokenSenderLogger.debug("Token File for parts not avail");
                if (!tokenchain.exists())
                    TokenSenderLogger.debug("Token Chain File for parts not avail");

                TokenSenderLogger.info("Tokens Not Verified");
                senderMutex = false;
                APIResponse.put("did", senderDidIpfsHash);
                APIResponse.put("tid", "null");
                APIResponse.put("status", "Failed");
                APIResponse.put("message", "Invalid part token(s)");
                return APIResponse;

            }
            String hash = add(tokenPath + partTokens.getString(i), ipfs);
            pin(hash, ipfs);


            String chainContent = readFile(tokenChainPath.concat(partTokens.getString(i)).concat(".json"));
            JSONArray chainArray = new JSONArray();
            JSONArray finalChainArray = new JSONArray(chainContent);
            for (int j = 0; j < finalChainArray.length(); j++) {
                JSONObject object = finalChainArray.getJSONObject(j);
                if (finalChainArray.length() == 1) {
                    object.put("previousHash", "");
                    object.put("nextHash", "");
                } else if (finalChainArray.length() > 1) {
                    if (j == 0) {
                        object.put("previousHash", "");
                        object.put("nextHash", calculateHash(finalChainArray.getJSONObject(j + 1).getString("tid"), "SHA3-256"));
                    } else if (j == finalChainArray.length() - 1) {
                        object.put("previousHash", calculateHash(finalChainArray.getJSONObject(j - 1).getString("tid"), "SHA3-256"));
                        object.put("nextHash", "");
                    } else {
                        object.put("previousHash", calculateHash(finalChainArray.getJSONObject(j - 1).getString("tid"), "SHA3-256"));
                        object.put("nextHash", calculateHash(finalChainArray.getJSONObject(j + 1).getString("tid"), "SHA3-256"));
                    }
                }
                chainArray.put(object);

            }
            writeToFile(tokenChainPath.concat(partTokens.getString(i)).concat(".json"), chainArray.toString(), false);

            partTokenChainHash.put(add(tokenChainPath.concat(partTokens.getString(i)).concat(".json"), ipfs));
        }

        String authSenderByRecHash = calculateHash(wholeTokens.toString() + wholeTokenChainHash.toString() + partTokens.toString() + partTokenChainHash.toString() + receiverDidIpfsHash + senderDidIpfsHash + comment, "SHA3-256");
        TokenSenderLogger.debug("Hash to verify Sender: " + authSenderByRecHash);
        String tid = calculateHash(authSenderByRecHash, "SHA3-256");
        TokenSenderLogger.debug("Sender by Receiver Hash " + authSenderByRecHash);
        TokenSenderLogger.debug("TID on sender " + tid);


        JSONArray quorumArray;
        JSONArray alphaQuorum = new JSONArray();
        JSONArray betaQuorum = new JSONArray();
        JSONArray gammaQuorum = new JSONArray();
        int alphaSize;

        ArrayList alphaPeersList;
        ArrayList betaPeersList;
        ArrayList gammaPeersList;

        long startTime = System.currentTimeMillis();
        switch (type) {
            case 1: {
                writeToFile(LOGGER_PATH + "tempbeta", tid.concat(senderDidIpfsHash), false);
                String betaHash = IPFSNetwork.add(LOGGER_PATH + "tempbeta", ipfs);
                deleteFile(LOGGER_PATH + "tempbeta");

                writeToFile(LOGGER_PATH + "tempgamma", tid.concat(receiverDidIpfsHash), false);
                String gammaHash = IPFSNetwork.add(LOGGER_PATH + "tempgamma", ipfs);
                deleteFile(LOGGER_PATH + "tempgamma");

                quorumArray = getQuorum(betaHash, gammaHash, senderDidIpfsHash, receiverDidIpfsHash, wholeTokens.length());
                break;
            }

            case 2: {
                quorumArray = new JSONArray(readFile(DATA_PATH + "quorumlist.json"));
                break;
            }
            case 3: {
                quorumArray = detailsObject.getJSONArray("quorum");
                break;
            }
            default: {
                TokenSenderLogger.error("Unknown quorum type input, cancelling transaction");
                APIResponse.put("status", "Failed");
                APIResponse.put("message", "Unknown quorum type input, cancelling transaction");
                return APIResponse;

            }
        }

        TokenSenderLogger.debug("1");
        TokenSenderLogger.debug("Whole tokens: " + wholeTokens);
        TokenSenderLogger.debug("Part tokens: " + partTokens);
        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        eventLogger.debug("Get Quorum List " + totalTime);

        startTime = System.currentTimeMillis();
        QuorumSwarmConnect(quorumArray, ipfs);
        endTime = System.currentTimeMillis();
        totalTime = endTime - startTime;
        eventLogger.debug("Swarm Connect " + totalTime);

        alphaSize = quorumArray.length() - 14;

        for (int i = 0; i < alphaSize; i++)
            alphaQuorum.put(quorumArray.getString(i));

        for (int i = 0; i < 7; i++) {
            betaQuorum.put(quorumArray.getString(alphaSize + i));
            gammaQuorum.put(quorumArray.getString(alphaSize + 7 + i));
        }
        startTime = System.currentTimeMillis();

        alphaPeersList = QuorumCheck(alphaQuorum, alphaSize);
        betaPeersList = QuorumCheck(betaQuorum, 7);
        gammaPeersList = QuorumCheck(gammaQuorum, 7);

        endTime = System.currentTimeMillis();
        totalTime = endTime - startTime;
        eventLogger.debug("Quorum Check " + totalTime);

        if (alphaPeersList.size() < minQuorum(alphaSize) || betaPeersList.size() < 5 || gammaPeersList.size() < 5) {
            updateQuorum(quorumArray, null, false, type);
            APIResponse.put("did", senderDidIpfsHash);
            APIResponse.put("tid", "null");
            APIResponse.put("status", "Failed");
            APIResponse.put("message", "Quorum Members not available");
            TokenSenderLogger.warn("Quorum Members not available");
            senderMutex = false;
            return APIResponse;
        }


        syncDataTable(receiverDidIpfsHash, null);
        receiverPeerId = getValues(DATA_PATH + "DataTable.json", "peerid", "didHash", receiverDidIpfsHash);

        if (!receiverPeerId.equals("")) {
            TokenSenderLogger.debug("Swarm connecting to " + receiverPeerId);
            swarmConnectP2P(receiverPeerId, ipfs);
            TokenSenderLogger.debug("Swarm connected");
        } else {
            APIResponse.put("did", senderDidIpfsHash);
            APIResponse.put("tid", "null");
            APIResponse.put("status", "Failed");
            APIResponse.put("message", "Receiver Peer ID null");
            TokenSenderLogger.warn("Receiver Peer ID null");
            senderMutex = false;
            return APIResponse;
        }

        String receiverWidIpfsHash = getValues(DATA_PATH + "DataTable.json", "walletHash", "didHash", receiverDidIpfsHash);
        if (!receiverWidIpfsHash.equals("")) {
            nodeData(receiverDidIpfsHash, receiverWidIpfsHash, ipfs);
        } else {
            APIResponse.put("did", senderDidIpfsHash);
            APIResponse.put("tid", "null");
            APIResponse.put("status", "Failed");
            APIResponse.put("message", "Receiver WID null");
            TokenSenderLogger.warn("Receiver WID null");
            senderMutex = false;
            return APIResponse;
        }

        TokenSenderLogger.debug("Sender IPFS forwarding to DID: " + receiverDidIpfsHash + " PeerID: " + receiverPeerId);
        forward(receiverPeerId, port, receiverPeerId);
        TokenSenderLogger.debug("Forwarded to " + receiverPeerId + " on " + port);
        senderSocket = new Socket("127.0.0.1", port);

        input = new BufferedReader(new InputStreamReader(senderSocket.getInputStream()));
        output = new PrintStream(senderSocket.getOutputStream());

        startTime = System.currentTimeMillis();

        /**
         * Sending Sender Peer ID to Receiver
         * Receiver to authenticate Sender's DID (Identity)
         */
        output.println(senderPeerID);
        TokenSenderLogger.debug("Sent PeerID");

        String peerAuth;
        try {
            peerAuth = input.readLine();
        } catch (SocketException e) {
            TokenSenderLogger.warn("Receiver " + receiverDidIpfsHash + " is unable to Respond! - Sender Auth");
            executeIPFSCommands(" ipfs p2p close -t /p2p/" + receiverPeerId);
            output.close();
            input.close();
            senderSocket.close();
            senderMutex = false;
            updateQuorum(quorumArray, null, false, type);
            APIResponse.put("did", senderDidIpfsHash);
            APIResponse.put("tid", "null");
            APIResponse.put("status", "Failed");
            APIResponse.put("message", "Receiver " + receiverDidIpfsHash + "is unable to respond! - Sender Auth");

            return APIResponse;
        }


        if (peerAuth != null && (!peerAuth.equals("200"))) {
            executeIPFSCommands(" ipfs p2p close -t /p2p/" + receiverPeerId);
            TokenSenderLogger.info("Sender Data Not Available");
            output.close();
            input.close();
            senderSocket.close();
            senderMutex = false;
            updateQuorum(quorumArray, null, false, type);
            APIResponse.put("did", senderDidIpfsHash);
            APIResponse.put("tid", tid);
            APIResponse.put("status", "Failed");
            APIResponse.put("message", "Sender Data Not Available");
            return APIResponse;

        }

        String senderSign = getSignFromShares(pvt, authSenderByRecHash);
        JSONObject senderDetails2Receiver = new JSONObject();
        senderDetails2Receiver.put("sign", senderSign);
        senderDetails2Receiver.put("tid", tid);
        senderDetails2Receiver.put("comment", comment);
        JSONObject partTokenChainArrays = new JSONObject();
        for (int i = 0; i < partTokens.length(); i++) {
            String chainContent = readFile(tokenChainPath.concat(partTokens.getString(i)).concat(".json"));
            JSONArray chainArray = new JSONArray(chainContent);
            JSONObject newLastObject = new JSONObject();
            if (chainArray.length() == 0) {
                newLastObject.put("previousHash", "");

            } else {
                JSONObject secondLastObject = chainArray.getJSONObject(chainArray.length() - 1);
                secondLastObject.put("nextHash", calculateHash(tid, "SHA3-256"));
                newLastObject.put("previousHash", calculateHash(chainArray.getJSONObject(chainArray.length() - 1).getString("tid"), "SHA3-256"));
            }

            Double amount = formatAmount(amountLedger.getDouble(partTokens.getString(i)));

            newLastObject.put("senderSign", senderSign);
            newLastObject.put("sender", senderDidIpfsHash);
            newLastObject.put("receiver", receiverDidIpfsHash);
            newLastObject.put("comment", comment);
            newLastObject.put("tid", tid);
            newLastObject.put("nextHash", "");
            newLastObject.put("role", "Sender");
            newLastObject.put("amount", amount);
            chainArray.put(newLastObject);
            partTokenChainArrays.put(partTokens.getString(i), chainArray);

        }

        JSONObject tokenDetails = new JSONObject();
        tokenDetails.put("whole-tokens", wholeTokens);
        tokenDetails.put("whole-tokenChains", wholeTokenChainHash);
        tokenDetails.put("hashSender", partTokenChainHash);
        tokenDetails.put("part-tokens", partTokens);
        tokenDetails.put("part-tokenChains", partTokenChainArrays);
        tokenDetails.put("sender", senderDidIpfsHash);
        String doubleSpendString = tokenDetails.toString();

        String doubleSpend = calculateHash(doubleSpendString, "SHA3-256");
        writeToFile(LOGGER_PATH + "doubleSpend", doubleSpend, false);
        TokenSenderLogger.debug("********Double Spend Hash*********:  " + doubleSpend);
        IPFSNetwork.addHashOnly(LOGGER_PATH + "doubleSpend", ipfs);
        deleteFile(LOGGER_PATH + "doubleSpend");


        JSONObject tokenObject = new JSONObject();
        tokenObject.put("tokenDetails", tokenDetails);
        tokenObject.put("previousSender", tokenPreviousSender);
        tokenObject.put("amount", requestedAmount);
        tokenObject.put("amountLedger", amountLedger);



        /**
         * Sending Token Details to Receiver
         * Receiver to authenticate Tokens (Double Spending, IPFS availability)
         */
        output.println(tokenObject);

        String tokenAuth;
        try {
            tokenAuth = input.readLine();
        } catch (SocketException e) {
            TokenSenderLogger.warn("Receiver " + receiverDidIpfsHash + " is unable to Respond! - Token Auth");
            executeIPFSCommands(" ipfs p2p close -t /p2p/" + receiverPeerId);
            output.close();
            input.close();
            senderSocket.close();
            senderMutex = false;
            updateQuorum(quorumArray, null, false, type);
            APIResponse.put("did", senderDidIpfsHash);
            APIResponse.put("tid", "null");
            APIResponse.put("status", "Failed");
            APIResponse.put("message", "Receiver " + receiverDidIpfsHash + "is unable to respond! - Token Auth");

            return APIResponse;
        }
        if (tokenAuth != null && (!tokenAuth.equals("200"))) {
            switch (tokenAuth) {
                case "420":
                    String doubleSpent = input.readLine();
                    String owners = input.readLine();
                    JSONArray ownersArray = new JSONArray(owners);
                    TokenSenderLogger.info("Multiple Owners for " + doubleSpent);
                    APIResponse.put("message", "Multiple Owners for " + doubleSpent);
                    APIResponse.put("Owners", ownersArray);
                    //removeToken();
                    break;
                case "421":
                    TokenSenderLogger.info("Consensus ID not unique");
                    APIResponse.put("message", "Consensus ID not unique");
                    //removeToken();
                    break;
                case "422":
                    TokenSenderLogger.info("Tokens Not Verified");
                    APIResponse.put("message", "Tokens Not Verified");
                    //removeToken();
                    break;
                case "423":
                    TokenSenderLogger.info("Broken Cheque Chain");
                    APIResponse.put("message", "Broken Cheque Chain");
                    break;

                case "424":
                    TokenSenderLogger.info("Token wholly spent already");
                    APIResponse.put("message", "Token wholly spent already");
                    break;

            }
            executeIPFSCommands(" ipfs p2p close -t /p2p/" + receiverPeerId);

            output.close();
            input.close();
            senderSocket.close();
            senderMutex = false;

            updateQuorum(quorumArray, null, false, type);
            APIResponse.put("did", senderDidIpfsHash);
            APIResponse.put("tid", tid);
            APIResponse.put("status", "Failed");
            return APIResponse;
        }

        JSONObject dataObject = new JSONObject();
        dataObject.put("tid", tid);
        dataObject.put("message", doubleSpendString);
        dataObject.put("receiverDidIpfs", receiverDidIpfsHash);
        dataObject.put("pvt", pvt);
        dataObject.put("senderDidIpfs", senderDidIpfsHash);
        dataObject.put("token", wholeTokens.toString());
        dataObject.put("alphaList", alphaPeersList);
        dataObject.put("betaList", betaPeersList);
        dataObject.put("gammaList", gammaPeersList);


        InitiatorProcedure.consensusSetUp(dataObject.toString(), ipfs, SEND_PORT + 100, alphaSize, "");
        TokenSenderLogger.debug("length on sender " + InitiatorConsensus.quorumSignature.length() + "response count " + InitiatorConsensus.quorumResponse);
        if (InitiatorConsensus.quorumSignature.length() < (minQuorum(alphaSize) + 2 * minQuorum(7))) {
            TokenSenderLogger.debug("Consensus Failed");
            senderDetails2Receiver.put("status", "Consensus Failed");
            output.println(senderDetails2Receiver);
            executeIPFSCommands(" ipfs p2p close -t /p2p/" + receiverPeerId);
            output.close();
            input.close();
            senderSocket.close();
            senderMutex = false;
            updateQuorum(quorumArray, null, false, type);
            APIResponse.put("did", senderDidIpfsHash);
            APIResponse.put("tid", tid);
            APIResponse.put("status", "Failed");
            APIResponse.put("message", "Transaction declined by Quorum");
            return APIResponse;

        }

        TokenSenderLogger.debug("Consensus Reached");
        senderDetails2Receiver.put("status", "Consensus Reached");
        senderDetails2Receiver.put("quorumsign", InitiatorConsensus.quorumSignature.toString());

        output.println(senderDetails2Receiver);
        TokenSenderLogger.debug("Quorum Signatures length " + InitiatorConsensus.quorumSignature.length());

        String signatureAuth;
        try {
            signatureAuth = input.readLine();
        } catch (SocketException e) {
            TokenSenderLogger.warn("Receiver " + receiverDidIpfsHash + " is unable to Respond! - Signature Auth");
            executeIPFSCommands(" ipfs p2p close -t /p2p/" + receiverPeerId);
            output.close();
            input.close();
            senderSocket.close();
            senderMutex = false;
            updateQuorum(quorumArray, null, false, type);
            APIResponse.put("did", senderDidIpfsHash);
            APIResponse.put("tid", "null");
            APIResponse.put("status", "Failed");
            APIResponse.put("message", "Receiver " + receiverDidIpfsHash + "is unable to respond! - Signature Auth");

            return APIResponse;
        }
        TokenSenderLogger.info("signatureAuth : " + signatureAuth);
        endTime = System.currentTimeMillis();
        totalTime = endTime - startTime;
        if (signatureAuth != null && (!signatureAuth.equals("200"))) {
            executeIPFSCommands(" ipfs p2p close -t /p2p/" + receiverPeerId);
            TokenSenderLogger.info("Authentication Failed");
            output.close();
            input.close();
            senderSocket.close();
            senderMutex = false;
            updateQuorum(quorumArray, null, false, type);
            APIResponse.put("did", senderDidIpfsHash);
            APIResponse.put("tid", tid);
            APIResponse.put("status", "Failed");
            APIResponse.put("message", "Sender not authenticated");
            return APIResponse;

        }

        for (int i = 0; i < wholeTokens.length(); i++)
            unpin(String.valueOf(wholeTokens.get(i)), ipfs);
        repo(ipfs);


        TokenSenderLogger.debug("Unpinned Tokens");
        output.println("Unpinned");
        String confirmation;
        try {
            confirmation = input.readLine();
        } catch (SocketException e) {
            TokenSenderLogger.warn("Receiver " + receiverDidIpfsHash + " is unable to Respond! - Pinning Auth");
            executeIPFSCommands(" ipfs p2p close -t /p2p/" + receiverPeerId);
            output.close();
            input.close();
            senderSocket.close();
            senderMutex = false;
            updateQuorum(quorumArray, null, false, type);
            APIResponse.put("did", senderDidIpfsHash);
            APIResponse.put("tid", "null");
            APIResponse.put("status", "Failed");
            APIResponse.put("message", "Receiver " + receiverDidIpfsHash + "is unable to respond! - Pinning Auth");

            return APIResponse;
        }
        if (confirmation != null && (!confirmation.equals("Successfully Pinned"))) {
            TokenSenderLogger.warn("Multiple Owners for the token");
            executeIPFSCommands(" ipfs p2p close -t /p2p/" + receiverPeerId);
            TokenSenderLogger.info("Tokens with multiple pins");
            output.close();
            input.close();
            senderSocket.close();
            senderMutex = false;
            updateQuorum(quorumArray, null, false, type);
            APIResponse.put("did", senderDidIpfsHash);
            APIResponse.put("tid", tid);
            APIResponse.put("status", "Failed");
            APIResponse.put("message", "Tokens with multiple pins");
            return APIResponse;

        }
        TokenSenderLogger.debug("3");
        TokenSenderLogger.debug("Whole tokens: " + wholeTokens);
        TokenSenderLogger.debug("Part tokens: " + partTokens);
        output.println(InitiatorProcedure.essential);
        String respAuth;
        try {
            respAuth = input.readLine();
        } catch (SocketException e) {
            TokenSenderLogger.warn("Receiver " + receiverDidIpfsHash + " is unable to Respond! - Share Confirmation");
            executeIPFSCommands(" ipfs p2p close -t /p2p/" + receiverPeerId);
            output.close();
            input.close();
            senderSocket.close();
            senderMutex = false;
            updateQuorum(quorumArray, null, false, type);
            APIResponse.put("did", senderDidIpfsHash);
            APIResponse.put("tid", "null");
            APIResponse.put("status", "Failed");
            APIResponse.put("message", "Receiver " + receiverDidIpfsHash + "is unable to respond! - Share Confirmation");

            return APIResponse;
        }

        if (respAuth != null && (!respAuth.equals("Send Response"))) {

            executeIPFSCommands(" ipfs p2p close -t /p2p/" + receiverPeerId);
            output.close();
            input.close();
            senderSocket.close();
            senderMutex = false;
            updateQuorum(quorumArray, null, false, type);
            APIResponse.put("did", senderDidIpfsHash);
            APIResponse.put("tid", tid);
            APIResponse.put("status", "Failed");
            APIResponse.put("message", "Receiver process not over");
            TokenSenderLogger.info("Incomplete Transaction");
            return APIResponse;

        }

        TokenSenderLogger.debug("Operation over");
        Iterator<String> keys = InitiatorConsensus.quorumSignature.keys();
        JSONArray signedQuorumList = new JSONArray();
        while (keys.hasNext())
            signedQuorumList.put(keys.next());
        APIResponse.put("tid", tid);
        APIResponse.put("status", "Success");
        APIResponse.put("did", senderDidIpfsHash);
        APIResponse.put("message", "Tokens transferred successfully!");
        APIResponse.put("quorumlist", signedQuorumList);
        APIResponse.put("receiver", receiverDidIpfsHash);
        APIResponse.put("totaltime", totalTime);

        updateQuorum(quorumArray, signedQuorumList, true, type);


        JSONArray allTokens = new JSONArray();
        for(int i = 0; i < wholeTokens.length(); i++)
            allTokens.put(wholeTokens.getString(i));
        for(int i = 0; i < partTokens.length(); i++)
            allTokens.put(partTokens.getString(i));

        TokenSenderLogger.debug("4");
        TokenSenderLogger.debug("All tokens: " + allTokens);
        TokenSenderLogger.debug("Whole tokens: " + wholeTokens);
        TokenSenderLogger.debug("Part tokens: " + partTokens);

        JSONObject transactionRecord = new JSONObject();
        transactionRecord.put("role", "Sender");
        transactionRecord.put("tokens", allTokens);
        transactionRecord.put("txn", tid);
        transactionRecord.put("quorumList", signedQuorumList);
        transactionRecord.put("senderDID", senderDidIpfsHash);
        transactionRecord.put("receiverDID", receiverDidIpfsHash);
        transactionRecord.put("Date", getCurrentUtcTime());
        transactionRecord.put("totalTime", totalTime);
        transactionRecord.put("comment", comment);
        transactionRecord.put("essentialShare", InitiatorProcedure.essential);
        requestedAmount = formatAmount(requestedAmount);
        transactionRecord.put("amount-spent", requestedAmount);


        JSONArray transactionHistoryEntry = new JSONArray();
        transactionHistoryEntry.put(transactionRecord);

        updateJSON("add", WALLET_DATA_PATH + "TransactionHistory.json", transactionHistoryEntry.toString());

        for (int i = 0; i < wholeTokens.length(); i++)
            Files.deleteIfExists(Paths.get(tokenPath + wholeTokens.get(i)));

        for (int i = 0; i < wholeTokens.length(); i++) {
            Functions.updateJSON("remove", PAYMENTS_PATH.concat("BNK00.json"), wholeTokens.getString(i));
        }

        if (newPart) {
            TokenSenderLogger.debug("Updating files for new parts");
            JSONObject newPartTokenObject = new JSONObject();
            newPartTokenObject.put("tokenHash", partTokens.getString(0));
            JSONArray newPartArray = new JSONArray();
            newPartArray.put(newPartTokenObject);
            writeToFile(PAYMENTS_PATH.concat("PartsToken.json"), newPartArray.toString(), false);

            String bankNew = readFile(PAYMENTS_PATH.concat("BNK00.json"));
            JSONArray bankNewArray = new JSONArray(bankNew);
            bankNewArray.remove(0);
            writeToFile(PAYMENTS_PATH.concat("BNK00.json"), bankNewArray.toString(), false);

            String newTokenChain = readFile(TOKENCHAIN_PATH + partTokens.getString(0) + ".json");
            JSONArray chainArray = new JSONArray(newTokenChain);

            JSONObject newLastObject = new JSONObject();
            if (chainArray.length() == 0) {
                newLastObject.put("previousHash", "");

            } else {
                JSONObject secondLastObject = chainArray.getJSONObject(chainArray.length() - 1);
                secondLastObject.put("nextHash", calculateHash(tid, "SHA3-256"));
                newLastObject.put("previousHash", calculateHash(chainArray.getJSONObject(chainArray.length() - 1).getString("tid"), "SHA3-256"));
            }

            Double amount = formatAmount(decimalAmount);

            newLastObject.put("senderSign", senderSign);
            newLastObject.put("sender", senderDidIpfsHash);
            newLastObject.put("receiver", receiverDidIpfsHash);
            newLastObject.put("comment", comment);
            newLastObject.put("tid", tid);
            newLastObject.put("nextHash", "");
            newLastObject.put("role", "Sender");
            newLastObject.put("amount", amount);
            chainArray.put(newLastObject);
            writeToFile(TOKENCHAIN_PATH + partTokens.getString(0) + ".json", chainArray.toString(), false);

            File tokenFile = new File(TOKENS_PATH.concat(partTokens.getString(0)));
            tokenFile.renameTo(new File(PART_TOKEN_PATH.concat(partTokens.getString(0))));
            File chainFile = new File(TOKENCHAIN_PATH.concat(partTokens.getString(0)).concat(".json"));
            chainFile.renameTo(new File(PART_TOKEN_CHAIN_PATH.concat(partTokens.getString(0)).concat(".json")));


            File shiftedFile = new File(PAYMENTS_PATH.concat("ShiftedTokens.json"));
            if (!shiftedFile.exists()) {
                shiftedFile.createNewFile();
                JSONArray shiftedTokensArray = new JSONArray();
                shiftedTokensArray.put(partTokens.getString(0));
                writeToFile(PAYMENTS_PATH.concat("ShiftedTokens.json"), shiftedTokensArray.toString(), false);
            } else {
                String shiftedContent = readFile(PAYMENTS_PATH.concat("ShiftedTokens.json"));
                JSONArray shiftedArray = new JSONArray(shiftedContent);
                shiftedArray.put(partTokens.getString(0));
                writeToFile(PAYMENTS_PATH.concat("ShiftedTokens.json"), shiftedArray.toString(), false);
            }
        } else {
            TokenSenderLogger.debug("Updating files for old parts");
            for (int i = 0; i < partTokens.length(); i++) {
                String newTokenChain = readFile(TOKENCHAIN_PATH.concat("PARTS/") + partTokens.getString(i) + ".json");
                JSONArray chainArray = new JSONArray(newTokenChain);

                JSONObject newLastObject = new JSONObject();
                if (chainArray.length() == 0) {
                    newLastObject.put("previousHash", "");

                } else {
                    JSONObject secondLastObject = chainArray.getJSONObject(chainArray.length() - 1);
                    secondLastObject.put("nextHash", calculateHash(tid, "SHA3-256"));
                    newLastObject.put("previousHash", calculateHash(chainArray.getJSONObject(chainArray.length() - 1).getString("tid"), "SHA3-256"));
                }

                TokenSenderLogger.debug("Amount from ledger: " + formatAmount(amountLedger.getDouble(partTokens.getString(i))));
                Double amount = formatAmount(amountLedger.getDouble(partTokens.getString(i)));

                newLastObject.put("senderSign", senderSign);
                newLastObject.put("sender", senderDidIpfsHash);
                newLastObject.put("receiver", receiverDidIpfsHash);
                newLastObject.put("comment", comment);
                newLastObject.put("tid", tid);
                newLastObject.put("nextHash", "");
                newLastObject.put("role", "Sender");
                newLastObject.put("amount", amount);
                chainArray.put(newLastObject);
                writeToFile(TOKENCHAIN_PATH.concat("PARTS/").concat(partTokens.getString(i)).concat(".json"), chainArray.toString(), false);

                TokenSenderLogger.debug("Checking Parts Token Balance ...");
                Double availableParts = partTokenBalance(partTokens.getString(i));
                TokenSenderLogger.debug("Available: " + availableParts);
                if (availableParts >= 1.000 || availableParts <= 0.000) {
                    TokenSenderLogger.debug("Wholly Spent, Removing token from parts");
                    String partFileContent2 = readFile(PAYMENTS_PATH.concat("PartsToken.json"));
                    JSONArray partContentArray2 = new JSONArray(partFileContent2);
                    for (int j = 0; j < partContentArray2.length(); j++) {
                        if (partContentArray2.getJSONObject(j).getString("tokenHash").equals(partTokens.getString(i)))
                            partContentArray2.remove(j);
                        writeToFile(PAYMENTS_PATH.concat("PartsToken.json"), partContentArray2.toString(), false);
                    }
                    deleteFile(PART_TOKEN_PATH.concat(partTokens.getString(i)));
                }
            }
            if(oldNew){
                String token = partTokens.getString(partTokens.length()-1);
                String bnk = readFile(PAYMENTS_PATH.concat("BNK00.json"));
                JSONArray bnkArray = new JSONArray(bnk);
                for(int i = 0; i < bnkArray.length(); i++){
                    if(bnkArray.getJSONObject(i).getString("tokenHash").equals(token))
                        bnkArray.remove(i);
                }
                writeToFile(PAYMENTS_PATH.concat("BNK00.json"), bnkArray.toString(), false);

                JSONArray pArray = new JSONArray();
                JSONObject pObject = new JSONObject();
                pObject.put("tokenHash", token);
                pArray.put(pObject);
                writeToFile(PAYMENTS_PATH.concat("PartsToken.json"), pArray.toString(), false);

            }
        }
        //Populating data to explorer
        if (!EXPLORER_IP.contains("127.0.0.1")) {

            List<String> tokenList = new ArrayList<>();
            for (int i = 0; i < allTokens.length(); i++)
                tokenList.add(allTokens.getString(i));
            String url = EXPLORER_IP + "/CreateOrUpdateRubixTransaction";
            URL obj = new URL(url);
            HttpsURLConnection con = (HttpsURLConnection) obj.openConnection();

            // Setting basic post request
            con.setRequestMethod("POST");
            con.setRequestProperty("Accept-Language", "en-US,en;q=0.5");
            con.setRequestProperty("Accept", "application/json");
            con.setRequestProperty("Content-Type", "application/json");
            con.setRequestProperty("Authorization", "null");

            // Serialization
            JSONObject dataToSend = new JSONObject();
            dataToSend.put("transaction_id", tid);
            dataToSend.put("sender_did", senderDidIpfsHash);
            dataToSend.put("receiver_did", receiverDidIpfsHash);
            dataToSend.put("token_id", tokenList);
            dataToSend.put("token_time", (int) totalTime);
            dataToSend.put("amount", requestedAmount);
            String populate = dataToSend.toString();

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("inputString", populate);
            String postJsonData = jsonObject.toString();

            // Send post request
            con.setDoOutput(true);
            DataOutputStream wr = new DataOutputStream(con.getOutputStream());
            wr.writeBytes(postJsonData);
            wr.flush();
            wr.close();

            int responseCode = con.getResponseCode();
            TokenSenderLogger.debug("Sending 'POST' request to URL : " + url);
            TokenSenderLogger.debug("Post Data : " + postJsonData);
            TokenSenderLogger.debug("Response Code : " + responseCode);

            BufferedReader in = new BufferedReader(
                    new InputStreamReader(con.getInputStream()));
            String output;
            StringBuffer response = new StringBuffer();

            while ((output = in.readLine()) != null) {
                response.append(output);
            }
            in.close();

            TokenSenderLogger.debug(response.toString());
        }


        TokenSenderLogger.info("Transaction Successful");
        executeIPFSCommands(" ipfs p2p close -t /p2p/" + receiverPeerId);
        output.close();
        input.close();
        senderSocket.close();
        senderMutex = false;
        return APIResponse;

    }
}
