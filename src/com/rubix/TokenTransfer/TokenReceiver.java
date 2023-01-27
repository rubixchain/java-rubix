package com.rubix.TokenTransfer;

import static com.rubix.Resources.Functions.*;
import static com.rubix.Resources.IPFSNetwork.*;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Random;

import javax.imageio.ImageIO;

import com.rubix.AuthenticateNode.Authenticate;
import com.rubix.AuthenticateNode.PropImage;
import com.rubix.Ping.VerifyStakedToken;
import com.rubix.Resources.Functions;
import com.rubix.Resources.IPFSNetwork;
import com.rubix.TokenTransfer.TransferPledge.Unpledge;

import Fork.ForkResolution;

import com.rubix.Constants.MiningConstants.*;

import static com.rubix.Resources.APIHandler.getPubKeyIpfsHash_DIDserver;
import static com.rubix.NFTResources.NFTFunctions.*;
import static com.rubix.TokenTransfer.TransferPledge.Unpledge.verifyProof;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.rubix.Constants.*;

import io.ipfs.api.IPFS;

public class TokenReceiver {
	public static Logger TokenReceiverLogger = Logger.getLogger(TokenReceiver.class);

	private static final JSONObject APIResponse = new JSONObject();
	private static IPFS ipfs = new IPFS("/ip4/127.0.0.1/tcp/" + IPFS_PORT);
	private static String SenWalletBin;

	// token limit for each level
	private static final int[] tokenLimit = { 0, 5000000, 2425000, 2303750, 2188563, 2079134, 1975178, 1876419, 1782598,
			1693468, 1608795, 1528355, 1451937, 1379340 };

	/**
	 * Receiver Node: To receive a valid token from an authentic sender
	 *
	 * @return Transaction Details (JSONObject)
	 * @throws IOException   handles IO Exceptions
	 * @throws JSONException handles JSON Exceptions
	 */
	public static String receive() {
		pathSet();
		ServerSocket ss = null;
		Socket sk = null;
		String senderPeerID = null;

		try {
			repo(ipfs);
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

			int quorumSignVerifyCount = 0;
			JSONArray quorumSignatures = null;

			ArrayList<String> quorumDID = new ArrayList<>();
			PropertyConfigurator.configure(LOGGER_PATH + "log4jWallet.properties");

			ArrayList<String> WholeTokenChainsWithAppendedBlock = new ArrayList<String>();
			JSONArray hash_Signs_ForTokenChains = new JSONArray();
			JSONArray hashes_Signs_PartTokenChains = new JSONArray();
			JSONObject final_parttokenchains = new JSONObject();

			String receiverPeerID = getPeerID(DATA_PATH + "DID.json");

			String receiverDidIpfsHash = getValues(DATA_PATH + "DataTable.json", "didHash", "peerid", receiverPeerID);

			JSONObject temp = new JSONObject();
			temp.put("pvtShare", DATA_PATH + receiverDidIpfsHash + "/PrivateShare.png");
			String pvt = temp.getString("pvtShare");

			listen(receiverPeerID, RECEIVER_PORT);
			ss = new ServerSocket(RECEIVER_PORT);
			TokenReceiverLogger.debug("Receiver Listening on " + RECEIVER_PORT + " appname " + receiverPeerID);

			sk = ss.accept();
			TokenReceiverLogger.debug("Data Incoming...");
			BufferedReader input = new BufferedReader(new InputStreamReader(sk.getInputStream()));
			PrintStream output = new PrintStream(sk.getOutputStream());
			long startTime = System.currentTimeMillis();

			try {
				senderPeerID = input.readLine();
			} catch (SocketException e) {
				TokenReceiverLogger.warn("Sender Stream Null - Sender Details");
				APIResponse.put("did", "");
				APIResponse.put("tid", "null");
				APIResponse.put("status", "Failed");
				APIResponse.put("message", "Sender Stream Null - Sender Details");

				output.close();
				input.close();
				sk.close();
				ss.close();
				return APIResponse.toString();
			}

			TokenReceiverLogger.debug("Data Received: " + senderPeerID);
			swarmConnectP2P(senderPeerID, ipfs);

			String senderDidIpfsHash = getValues(DATA_PATH + "DataTable.json", "didHash", "peerid", senderPeerID);
			String senderWidIpfsHash = getValues(DATA_PATH + "DataTable.json", "walletHash", "peerid", senderPeerID);

			if (!(senderDidIpfsHash.contains("Qm") && senderWidIpfsHash.contains("Qm"))) {
				output.println("420");
				APIResponse.put("did", senderDidIpfsHash);
				APIResponse.put("tid", "null");
				APIResponse.put("status", "Failed");
				APIResponse.put("message", "Sender details not available in network , please sync");
				TokenReceiverLogger.info("Sender details not available in datatable");
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
				/* executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID); */
				output.close();
				input.close();
				sk.close();
				ss.close();
				return APIResponse.toString();
			}
			TokenReceiverLogger.debug("Sender details authenticated");
			output.println("200");

			String tokenDetails;
			try {
				tokenDetails = input.readLine();
			} catch (SocketException e) {
				TokenReceiverLogger.warn("Sender Stream Null - Token Details");
				APIResponse.put("did", "");
				APIResponse.put("tid", "null");
				APIResponse.put("status", "Failed");
				APIResponse.put("message", "Sender Stream Null - Token Details");

				output.close();
				input.close();
				sk.close();
				ss.close();
				return APIResponse.toString();

			}
			JSONObject tokenObject = new JSONObject(tokenDetails);
			JSONObject TokenDetails = tokenObject.getJSONObject("tokenDetails");
			JSONArray wholeTokens = TokenDetails.getJSONArray("whole-tokens");
			JSONArray wholeTokenChains = TokenDetails.getJSONArray("whole-tokenChains");

			JSONArray partTokens = TokenDetails.getJSONArray("part-tokens");
			JSONObject partTokenChains = TokenDetails.getJSONObject("part-tokenChains");
			JSONObject partTokenChainsForVerification = TokenDetails.getJSONObject("part-tokenChains-PrevState");
			JSONArray partTokenChainsHash = TokenDetails.getJSONArray("hashSender");
			JSONObject distributedObject = TokenDetails.getJSONObject("distributedObject");


			if (TokenDetails.optJSONArray("proof") != null) {
				JSONArray proofArray = TokenDetails.getJSONArray("proof");

				for (int i = 0; i < proofArray.length(); i++) {
					String token = proofArray.getJSONObject(i).getString("token");
					String cid = proofArray.getJSONObject(i).getString("cid");
					String proof = get(cid, ipfs);
					File proofFile = new File(TOKENCHAIN_PATH + "Proof/");
					if (!proofFile.exists()) {
						proofFile.mkdirs();
					}
					//TOKENCHAIN_PATH + "Proof/"+ token + ".proof"
					writeToFile(proofFile.getAbsolutePath()+"/"+ token + ".proof", proof, false);
					TokenReceiverLogger.debug(proofFile.getAbsolutePath());
				}
			}

			JSONArray previousSendersArray = tokenObject.getJSONArray("previousSender");
//			 JSONArray positionsArray = tokenObject.getJSONArray("positions");

			Double amount = tokenObject.getDouble("amount");
			JSONObject amountLedger = tokenObject.getJSONObject("amountLedger");
			TokenReceiverLogger.debug("Amount Ledger: " + amountLedger);
			int intPart = wholeTokens.length();
			// ? multiple pin check starts
			Double decimalPart = formatAmount(amount - intPart);
			JSONArray doubleSpentToken = new JSONArray();
			boolean multiplePin = false;
			ArrayList pinOwnersArray = new ArrayList();
			ArrayList previousSender = new ArrayList();
			JSONArray ownersReceived = new JSONArray();
			//TokenReceiverLogger.debug("previousSendersArray is " + previousSendersArray.toString());
			//TokenReceiverLogger.debug("tokenObject is " + tokenObject.toString());
			//TokenReceiverLogger.debug("tokenDetails(base for tokenObj) is " + tokenDetails.toString());

			// previoussenderarray
			// tokenobject
			for (int i = 0; i < wholeTokens.length(); ++i) {
				try {
					TokenReceiverLogger.debug("Checking owners for " + wholeTokens.getString(i) + " Please wait...");
					pinOwnersArray = IPFSNetwork.dhtOwnerCheck(wholeTokens.getString(i));

					if (pinOwnersArray.size() > 2) {

						for (int j = 0; j < previousSendersArray.length(); j++) {
							if (previousSendersArray.getJSONObject(j).getString("token")
									.equals(wholeTokens.getString(i)))
								ownersReceived = previousSendersArray.getJSONObject(j).getJSONArray("sender");
						}

						for (int j = 0; j < ownersReceived.length(); j++) {
							previousSender.add(ownersReceived.getString(j));
						}
						TokenReceiverLogger.debug("Previous Owners: " + previousSender.toString());
						TokenReceiverLogger.debug("Pin owners " + pinOwnersArray.toString());


						ArrayList retainOrder = pinOwnersArray;
						previousSender.add(senderPeerID);
	                    previousSender.add(receiverPeerID);
	                    
	                    retainOrder.removeAll(previousSender);
                        FunctionsLogger.debug("retain list "+ retainOrder.toString());


	                    if(retainOrder.size()>0) {
	                        FunctionsLogger.debug("retain list in if "+ retainOrder.toString());
	                        multiplePin = true;
	                        
	                    }
					}
				} catch (IOException e) {

					TokenReceiverLogger.debug("Ipfs dht find did not execute");
				}
			}
//			if (multiplePin) {
//				JSONArray owners = new JSONArray();
//				for (int i = 0; i < pinOwnersArray.size(); i++)
//					owners.put(pinOwnersArray.get(i).toString());
//				TokenReceiverLogger.debug("Multiple Owners for " + doubleSpentToken);
//				TokenReceiverLogger.debug("Owners: " + owners);
//				output.println("420");
//				output.println(doubleSpentToken.toString());
//				output.println(owners.toString());
//				APIResponse.put("did", senderDidIpfsHash);
//				APIResponse.put("tid", "null");
//				APIResponse.put("status", "Failed");
//				APIResponse.put("message", "Multiple Owners for " + doubleSpentToken + " " + owners);
//				IPFSNetwork.executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
//				output.close();
//				input.close();
//				sk.close();
//				ss.close();
//				return APIResponse.toString();
//			}
			// ? multiple pin check ends
			String senderToken = TokenDetails.toString();
			String consensusID = calculateHash(senderToken, "SHA3-256");
			TokenReceiverLogger.debug("consensusID is " + consensusID);
			writeToFile(LOGGER_PATH + "consensusID", consensusID, false);
			String consensusIDIPFSHash = IPFSNetwork.addHashOnly(LOGGER_PATH + "consensusID", ipfs);
			deleteFile(LOGGER_PATH + "consensusID");

			if (!IPFSNetwork.dhtEmpty(consensusIDIPFSHash, ipfs)) {
				TokenReceiverLogger.debug("consensus ID not unique" + consensusIDIPFSHash);
				output.println("421");
				APIResponse.put("did", senderDidIpfsHash);
				APIResponse.put("tid", "null");
				APIResponse.put("status", "Failed");
				APIResponse.put("message", "Consensus ID not unique");
				IPFSNetwork.executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
				output.close();
				input.close();
				sk.close();
				ss.close();
				return APIResponse.toString();
			}

			// Check IPFS get for all Tokens
			int ipfsGetFlag = 0;
			ArrayList<String> wholeTokenContent = new ArrayList<>();
			ArrayList<String> wholeTokenChainContent = new ArrayList<>();

			/** Token Authenticity Check - starts */
			HashMap<String, Integer> tokenMaxLimitMap = new HashMap<>();
			HashMap<String, Integer> tokenDetailMap = new HashMap<>();

			for (int i = 0; i < wholeTokens.length(); i++) {

				String tokenChainContent = get(wholeTokenChains.getString(i), ipfs);
				wholeTokenChainContent.add(tokenChainContent);
				String tokenContent = get(wholeTokens.getString(i), ipfs).trim();
				wholeTokenContent.add(tokenContent);
				ipfsGetFlag++;
				String tokenLevel = tokenContent.substring(0, tokenContent.length() - 64);
				String tokenNumberHash = tokenContent.substring(tokenContent.length() - 64);

				int tokenLevelInt = Integer.parseInt(tokenLevel);
				int tokenLimitForLevel = tokenLimit[tokenLevelInt];
				tokenMaxLimitMap.put(tokenNumberHash, tokenLimitForLevel);
				tokenDetailMap.put(tokenNumberHash, -1);
			}
			
			if (intPart > 0) {

				boolean forkResolution = true;
				TokenReceiverLogger.debug("Fork Checking version 3: ");
		//		TokenReceiverLogger.debug("Whole Token Chains: " + wholeTokenChainContent.get(0));
		//		TokenReceiverLogger.debug("Whole Token Chains: " + new JSONArray(wholeTokenChainContent.get(0)));
				JSONArray forkedTokens = new JSONArray();
				for (int i = 0; i < wholeTokens.length(); ++i) {
					TokenReceiverLogger.debug("Fork Checking for token: " + wholeTokens.getString(i));

					JSONArray tokenChain = new JSONArray(wholeTokenChainContent.get(i));
					JSONObject forkObject = new JSONObject();
					forkObject.put("token", wholeTokens.getString(i));
					forkObject.put("tokenChain", tokenChain);
					forkObject.put("previousSendersArray", previousSendersArray);
					forkObject.put("currentSender", senderPeerID);
					forkObject.put("currentReceiver", receiverPeerID);

					if (tokenChain.getJSONObject(tokenChain.length() - 1).has("pledgeToken")) {
						TokenReceiverLogger
								.debug("Token " + wholeTokens.getString(i) + " is a new token (SHAG structure)");
						if (!tokenChain.getJSONObject(tokenChain.length() - 1).getString("pledgeToken").equals("")) {
							TokenReceiverLogger.debug("Token " + wholeTokens.getString(i) + " is a pledged token");
							forkResolution = ForkResolution.verifyUnpledgedToken(wholeTokens.getString(i),
									tokenChain.getJSONObject(tokenChain.length() - 1).getJSONArray("tokensPledgedFor")
											.get(0).toString(), senderPeerID, receiverPeerID);

						} else {
							TokenReceiverLogger.debug("Token " + wholeTokens.getString(i)
									+ " is a new token (SHAG structure) and not pledged");
							forkResolution = ForkResolution.check(forkObject);
						}
					} else {
						TokenReceiverLogger.debug("Token " + wholeTokens.getString(i) + " is an Eismeer Token");
						forkResolution = ForkResolution.check(forkObject);
					}
					if (!forkResolution) {
						TokenReceiverLogger.debug("Fork found and not resolved for token: " + wholeTokens.getString(i));
						TokenReceiverLogger.debug("Message: " + ForkResolution.resolutionMessage);
						forkedTokens.put(wholeTokens.getString(i));
					}
				}
				if (!forkResolution) {
					TokenReceiverLogger.debug("Fork not resolved for " + forkedTokens);
					output.println("418");
					output.println(forkedTokens.toString());
					APIResponse.put("did", senderDidIpfsHash);
					APIResponse.put("tid", "null");
					APIResponse.put("status", "Failed");
					APIResponse.put("message", "Fork not resolved");
					IPFSNetwork.executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
					output.close();
					input.close();
					sk.close();
					ss.close();
					return APIResponse.toString();
				}
			}


			//QmWcmK38g9XcrCwhoEq3rQjNseJW2HyscoG66DWF62X6bK.proof
			//BNK00.json -- unpledge
			//
			repo(ipfs);
			String pledgeErrorMessage = "";
			if (wholeTokens.length() > 0) {
				boolean pledged = true;
				JSONArray pledgedTokens = new JSONArray();
				for (int i = 0; i < wholeTokens.length(); i++) {
					JSONArray chain = new JSONArray(wholeTokenChainContent.get(i));
					if (chain.length() > 0) {
						JSONObject pledgeBlock = chain.getJSONObject(chain.length() - 1);
						//TokenReceiverLogger.debug("pledgeBlock object " + pledgeBlock.toString());
						// if (pledgeBlock.has("pledgeToken")) {

						if (pledgeBlock.optString("pledgeToken").length() > 0) {
							pledged = false;
							String tokenName = chain.getJSONObject(chain.length() - 1).getString("pledgeToken");
							String did = chain.getJSONObject(chain.length() - 1).getString("receiver");
							String tid = chain.getJSONObject(chain.length() - 1).getString("tid");
							String path = TOKENCHAIN_PATH + "Proof/"+ tokenName + ".proof";
							File proofFile = new File(path);
							if(proofFile.exists()){
								pledged = Unpledge.verifyProof(tokenName, did, tid);
							}
							JSONObject pledgedTokenObject = new JSONObject();
							pledgedTokenObject.put("tokenHash", wholeTokens.getString(i));
							pledgedTokens.put(pledgedTokenObject);
						}
						// }
					}
				}
				if (!pledged) {
					output.println("419");
					output.println(pledgedTokens.toString());
					APIResponse.put("did", senderDidIpfsHash);
					APIResponse.put("tid", "null");
					APIResponse.put("status", "Failed");
					APIResponse.put("message", pledgeErrorMessage);
					TokenReceiverLogger.info(pledgeErrorMessage);
					IPFSNetwork.executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
					output.close();
					input.close();
					sk.close();
					ss.close();
					return APIResponse.toString();
				}

				if (!(ipfsGetFlag == intPart)) {
					output.println("422");
					APIResponse.put("did", senderDidIpfsHash);
					APIResponse.put("tid", "null");
					APIResponse.put("status", "Failed");
					APIResponse.put("message", "Tokens not verified");
					TokenReceiverLogger.info("Tokens not verified");
					IPFSNetwork.executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
					output.close();
					input.close();
					sk.close();
					ss.close();
					return APIResponse.toString();
				}

				int tokenMaxValue = Collections.max(tokenMaxLimitMap.values());
				TokenReceiverLogger.debug("Token Max Value : " + tokenMaxValue);
				tokenDetailMap = Functions.checkTokenHash(tokenDetailMap, tokenMaxValue);

				if (tokenDetailMap.isEmpty()) {
					TokenReceiverLogger.debug("Invalid Content Found in Token");
					String errorMessage = "Invalid Content Found in Token";
					output.println("426");
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
				} else {
					for (String tokenContent : tokenDetailMap.keySet()) {
						//TokenReceiverLogger.debug(tokenContent);
						//TokenReceiverLogger.debug(tokenDetailMap.get(tokenContent));
						//TokenReceiverLogger.debug(tokenMaxLimitMap.get(tokenContent));

						if (tokenDetailMap.get(tokenContent) != null
								&& tokenDetailMap.get(tokenContent) > tokenMaxLimitMap.get(tokenContent)) {

							output.println("426");
							APIResponse.put("did", senderDidIpfsHash);
							APIResponse.put("tid", "null");
							APIResponse.put("status", "Failed");
							String errorMessage1 = "Token Number is greater than Token Limit for the Level";
							APIResponse.put("message", errorMessage1);
							TokenReceiverLogger.debug(errorMessage1);
							executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
							output.close();
							input.close();
							sk.close();
							ss.close();
							return APIResponse.toString();
						}
					}
				}

			}
			/** Token Authenticity Check - Ends */

			// Token Chains authenticity check starts

			TokenReceiverLogger.debug("VERIFYING TOKEN CHAIN(S).. ");

			// For WholeTokenChains
			int chainAutheticityFlag = 0;
			boolean flag = false;
			TokenReceiverLogger.debug("Verifying whole token chains.. ");
			for (int i = 0; i < intPart; i++) {

				//TokenReceiverLogger.debug("Verifying whole Token Chain : " + wholeTokenChains.getString(i));

				String TokenChainContent = get(wholeTokenChains.getString(i), ipfs);

				JSONArray TokenChainArray = new JSONArray(TokenChainContent);
				// TokenReceiverLogger.debug("Token Chain content fetched from ipfs for
				// tokenchain"+i+" : "+ TokenChainArray);

				JSONObject lastValueOnChain = TokenChainArray.getJSONObject(TokenChainArray.length() - 1);

				JSONObject forNLSScheck = new JSONObject();

//				if(lastValueOnChain.has("hash") && lastValueOnChain.has("pvtShareBits") && lastValueOnChain.has("pvtKeySign")){

				if (lastValueOnChain.has("hash") && lastValueOnChain.has("pvtShareBits")) {

					String hash = lastValueOnChain.getString("hash");
					TokenReceiverLogger.debug("Hash to check(hash)  : " + hash);
					String pvtShareBits = lastValueOnChain.getString("pvtShareBits");
					// String pvtKeySign = lastValueOnChain.getString("pvtKeySign");
					String prevSenderDID = lastValueOnChain.getString("sender");

					// Editing last object before calc hash.

					JSONObject lastObj = new JSONObject();
					lastObj = TokenChainArray.getJSONObject(TokenChainArray.length() - 1);
					TokenChainArray.remove(TokenChainArray.length() - 1);
					lastObj.remove("hash");
					lastObj.remove("pvtShareBits");
					// lastObj.remove("pvtKeySign");

					TokenChainArray.put(lastObj);

					// TokenReceiverLogger.debug("Token Chain Content that is to be hashed :
					// "+TokenChainArray);

					String hashToCheck = calculateHash(TokenChainArray.toString(), "SHA3-256");
					TokenReceiverLogger.debug("@@@@@@ hashtocheck is " + hashToCheck);

					TokenReceiverLogger.debug("@@@@@@ Calculated Hash(hashtocheck) : " + hashToCheck);

					//TokenReceiverLogger.debug("@@@@@@ ReCalc tokenchain: " + TokenChainArray);

					// Check 1 : Hash comaprison check
					if (hashToCheck.equals(hash)) {

						TokenReceiverLogger.debug("Hash values match.");

						// Check to ensure the sender hasnt changed the DID on the second last block to
						// their DID instead of the prev sender.
						// If the above is done, the sender cn easily change the contents of the chain
						// and, hash and sign, and the receiver wont know.
						// Hence the check.
						if (prevSenderDID.equals(senderDidIpfsHash)) {
							chainAutheticityFlag = 3;
							break;
						}

						forNLSScheck.put("did", prevSenderDID);
						forNLSScheck.put("hash", hashToCheck);
						forNLSScheck.put("signature", pvtShareBits);

						String prevSenderPublicKeyIpfsHash = getPubKeyIpfsHash_DIDserver(prevSenderDID, 1); // get
																											// public
																											// key ipfs
																											// hash of
																											// the
																											// sender.
						// Need to handle the case where DID server isnt available.

						String prevSenderPubKeyStr = IPFSNetwork.get(prevSenderPublicKeyIpfsHash, ipfs); // get sender's
																											// public
																											// key from
																											// ipfs.
						// Need to handle the case when ipfs isn't avaialble.

						String pubKeyAlgo = publicKeyAlgStr(prevSenderPubKeyStr);

						flag = Authenticate.verifySignature(forNLSScheck.toString());
						if (!flag) {

							chainAutheticityFlag = 5;
							break;
						} else {
							TokenReceiverLogger.debug("Previous sender's sign also authenticated.");
							//TokenReceiverLogger.debug("Token chain " + wholeTokenChains.getString(i) + "verified");
						}

						// Check 2: prev Sender authenticity check (Signature)
						/*
						 * if(verifySignature(pvtShareBits,getPubKeyFromStr(prevSenderPubKeyStr,
						 * pubKeyAlgo),pvtKeySign,pubKeyAlgo)==true){
						 * 
						 * flag = Authenticate.verifySignature(forNLSScheck.toString()); if(!flag){
						 * 
						 * chainAutheticityFlag = 5; break; }else{
						 * TokenReceiverLogger.debug("Previous sender's sign also authenticated.");
						 * TokenReceiverLogger.debug("Token chain "+wholeTokenChains.getString(i)+
						 * "verified"); }
						 * 
						 * } else {
						 * 
						 * //If private key signature verification of prev sender doesn't complete.
						 * chainAutheticityFlag = 4; break; }
						 */

					} else {

						// If hash values dont match
						chainAutheticityFlag = -1;
						break;
					}

				} else {
					// If the hash and sign are not present in the TokenChain
					chainAutheticityFlag = 1;
					break;
				}
			}

			// Returning responses after chain verification
			if (chainAutheticityFlag == 1) {

				TokenReceiverLogger.debug(
						"Token Transfer continuing without TokenChain authentication check. (Bootstrap purpose)");
				// if condition needs to be removed after bootstrapping.
			}

			// If hash values for tokenchain authenticity checks have a mismatch.
			if (chainAutheticityFlag == 2) {
				APIResponse.put("did", senderDidIpfsHash);
				APIResponse.put("tid", "null");
				APIResponse.put("status", "Failed");
				APIResponse.put("message", "Token Chain could not be verified");
				TokenReceiverLogger.info("Token Chain could not be verified.");
				// TokenReceiverLogger.info("Chain auth flag : "+chainAutheticityFlag);

				output.println("430");
				IPFSNetwork.executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
				output.close();
				input.close();
				sk.close();
				ss.close();
				return APIResponse.toString();
			}

			// If sender tries to edit the token chain with the hash and the sign. Ideally
			// by putting his DID instead of prev sender's DID in the last appended block.
			if (chainAutheticityFlag == 3) {
				APIResponse.put("did", senderDidIpfsHash);
				APIResponse.put("tid", "null");
				APIResponse.put("status", "Failed");
				APIResponse.put("message", "Token Chain could not be verified. Malicious activity by sender");
				TokenReceiverLogger.info("Token Chain could not be verified. Malicious activity by sender");
				// TokenReceiverLogger.info("Chain auth flag : "+chainAutheticityFlag);

				output.println("430");
				IPFSNetwork.executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
				output.close();
				input.close();
				sk.close();
				ss.close();
				return APIResponse.toString();
			}

			if (chainAutheticityFlag == 4) {
				APIResponse.put("did", senderDidIpfsHash);
				APIResponse.put("tid", "null");
				APIResponse.put("status", "Failed");
				APIResponse.put("message",
						"Token Chain could not be verified. Previous sender's signature not verified");
				TokenReceiverLogger.info("Token Chain could not be verified. Previous sender's signature not verified");
				// TokenReceiverLogger.info("Chain auth flag : "+chainAutheticityFlag);

				output.println("430");
				IPFSNetwork.executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
				output.close();
				input.close();
				sk.close();
				ss.close();
				return APIResponse.toString();
			}

			if (chainAutheticityFlag == 5) {
				APIResponse.put("did", senderDidIpfsHash);
				APIResponse.put("tid", "null");
				APIResponse.put("status", "Failed");
				APIResponse.put("message",
						"Token Chain could not be verified. Previous sender's signature not verified");
				TokenReceiverLogger.info("Token Chain could not be verified. Previous sender's signature not verified");
				// TokenReceiverLogger.info("Chain auth flag : "+chainAutheticityFlag);

				output.println("430");
				IPFSNetwork.executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
				output.close();
				input.close();
				sk.close();
				ss.close();
				return APIResponse.toString();
			}

			if (flag) {
				TokenReceiverLogger.debug("Whole Token Chains successfully verified !");
			}

			// For PartTokenChains
			int chainAutheticityFlag2 = 0;
			boolean flag2 = false;
			TokenReceiverLogger.debug("Verifying part token chains.. ");
			for (int i = 0; i < partTokens.length(); i++) {

				JSONObject forNLSScheck = new JSONObject();
				JSONArray partTokenChainToVerify = new JSONArray();
				JSONObject lastValueOnChain2 = new JSONObject();
				TokenReceiverLogger.debug("Verifying part Token Chain : " + partTokens.getString(i));

				partTokenChainToVerify = partTokenChainsForVerification.getJSONArray(partTokens.getString(i));

				lastValueOnChain2 = partTokenChainToVerify.getJSONObject(partTokenChainToVerify.length() - 1);

				if ((lastValueOnChain2.has("role"))) {

					if (lastValueOnChain2.getString("role").equals("Sender")) {

						if (lastValueOnChain2.has("hash") && lastValueOnChain2.has("pvtShareBits")) {

							String hash = lastValueOnChain2.getString("hash");
							TokenReceiverLogger.debug("Hash to check  : " + hash);

							String pvtShareBits = lastValueOnChain2.getString("pvtShareBits");

							String requiredDID = lastValueOnChain2.getString("receiver");

							// Editing last object before calc hash.

							JSONObject lastObj2 = new JSONObject();
							lastObj2 = partTokenChainToVerify.getJSONObject(partTokenChainToVerify.length() - 1);
							partTokenChainToVerify.remove(partTokenChainToVerify.length() - 1);
							lastObj2.remove("hash");
							lastObj2.remove("pvtShareBits");
							// lastObj.remove("pvtKeySign");

							partTokenChainToVerify.put(lastObj2);

							//TokenReceiverLogger.debug("Token Chain to be hashed : " + partTokenChainToVerify);

							String hashToCheck = calculateHash(partTokenChainToVerify.toString(), "SHA3-256");
							TokenReceiverLogger.debug("Calculated Hash(hashToCheck) : " + hashToCheck);

							if (hashToCheck.equals(hash)) {
								TokenReceiverLogger.debug("Hash values match.");

								forNLSScheck.put("did", requiredDID);
								forNLSScheck.put("hash", hashToCheck);
								forNLSScheck.put("signature", pvtShareBits);

								flag2 = Authenticate.verifySignature(forNLSScheck.toString());
								if (!flag2) {

									chainAutheticityFlag2 = 5;
									break;
								}

							} else {

								// If hash values dont match
								chainAutheticityFlag2 = 2;
								break;
							}
						} else {
							// If the hash and sign are not present in the TokenChain
							chainAutheticityFlag2 = 1;
							break;
						}
					}

					else {

						if (lastValueOnChain2.has("hash") && lastValueOnChain2.has("pvtShareBits")) {

							String hash = lastValueOnChain2.getString("hash");
							// TokenReceiverLogger.debug("Hash to check : "+ hash);

							String pvtShareBits = lastValueOnChain2.getString("pvtShareBits");
							// String pvtKeySign = lastValueOnChain.getString("pvtKeySign");

							String requiredDID = lastValueOnChain2.getString("sender");

							// Editing last object before calc hash.
							JSONObject lastObj3 = new JSONObject();
							JSONObject newobj3 = new JSONObject();
							lastObj3 = partTokenChainToVerify.getJSONObject(partTokenChainToVerify.length() - 1);

							partTokenChainToVerify.remove(partTokenChainToVerify.length() - 1);

							lastObj3.remove("hash");
							lastObj3.remove("pvtShareBits");

							partTokenChainToVerify.put(lastObj3);

							// TokenReceiverLogger.debug("Token chain to be hashed (AFTER EDITING LAST
							// BLOCK) : "+ partTokenChainToVerify);

							String hashToCheck = calculateHash(partTokenChainToVerify.toString(), "SHA3-256");
							TokenReceiverLogger.debug("Calculated Hash : " + hashToCheck);

							if (hashToCheck.equals(hash)) {
								// TokenReceiverLogger.debug("Hash values match.");

								forNLSScheck.put("did", requiredDID);
								forNLSScheck.put("hash", hashToCheck);
								forNLSScheck.put("signature", pvtShareBits);

								flag2 = Authenticate.verifySignature(forNLSScheck.toString());
								if (!flag2) {

									chainAutheticityFlag2 = 5;
									break;
								}

							} else {

								// If hash values dont match
								chainAutheticityFlag2 = 2;
								break;
							}

						} else {
							// If the hash and sign are not present in the TokenChain
							chainAutheticityFlag2 = 1;
							break;
						}
					}

				} else if (lastValueOnChain2.has("hash") && lastValueOnChain2.has("pvtShareBits")) {

					// else if (lastValueOnChain2.has("hash") &&
					// lastValueOnChain2.has("pvtShareBits") &&
					// lastValueOnChain2.has("pvtKeySign")){

					String hash = lastValueOnChain2.getString("hash");
					// TokenReceiverLogger.debug("Hash to check : "+ hash);
					String pvtShareBits = lastValueOnChain2.getString("pvtShareBits");
					// String pvtKeySign = lastValueOnChain2.getString("pvtKeySign");
					String prevSenderDID = lastValueOnChain2.getString("sender");

					// Editing last object before calc hash.

					JSONObject lastObj4 = new JSONObject();
					lastObj4 = partTokenChainToVerify.getJSONObject(partTokenChainToVerify.length() - 1);
					partTokenChainToVerify.remove(partTokenChainToVerify.length() - 1);
					lastObj4.remove("hash");
					lastObj4.remove("pvtShareBits");
					// lastObj4.remove("pvtKeySign");

					partTokenChainToVerify.put(lastObj4);

					// TokenReceiverLogger.debug("Token Chain Content that is to be hashed :
					// "+TokenChainArray);

					String hashToCheck = calculateHash(partTokenChainToVerify.toString(), "SHA3-256");
					//TokenReceiverLogger.debug("hashToCheck(partTokenChainToVerify.toString()) is " + hashToCheck);

					if (hashToCheck.equals(hash)) {

						TokenReceiverLogger.debug("Hash values match.");

						// Check to ensure the sender hasnt changed the DID on the second last block to
						// their DID instead of the prev sender.
						// If the above is done, the sender cn easily change the contents of the chain
						// and, hash and sign, and the receiver wont know.
						// Hence the check.
						if (prevSenderDID.equals(senderDidIpfsHash)) {
							chainAutheticityFlag = 3;
							break;
						}

						forNLSScheck.put("did", prevSenderDID);
						forNLSScheck.put("hash", hashToCheck);
						forNLSScheck.put("signature", pvtShareBits);

						String prevSenderPublicKeyIpfsHash = getPubKeyIpfsHash_DIDserver(prevSenderDID, 1); // get
																											// public
																											// key ipfs
																											// hash of
																											// the
																											// sender.
						// Need to handle the case where DID server isnt available.

						String prevSenderPubKeyStr = IPFSNetwork.get(prevSenderPublicKeyIpfsHash, ipfs); // get sender's
																											// public
																											// key from
																											// ipfs.
						// Need to handle the case when ipfs isn't avaialble.

						String pubKeyAlgo = publicKeyAlgStr(prevSenderPubKeyStr);

						flag = Authenticate.verifySignature(forNLSScheck.toString());
						if (!flag) {

							chainAutheticityFlag = 5;
							break;
						} else {
							TokenReceiverLogger.debug("Previous sender's sign also authenticated.");
							//TokenReceiverLogger.debug("Token chain " + wholeTokenChains.getString(i) + "verified");
						}

						/*
						 * //Check 2: prev Sender authenticity check (Signature)
						 * if(verifySignature(pvtShareBits,getPubKeyFromStr(prevSenderPubKeyStr,
						 * pubKeyAlgo),pvtKeySign,pubKeyAlgo)==true){
						 * 
						 * flag = Authenticate.verifySignature(forNLSScheck.toString()); if(!flag){
						 * 
						 * chainAutheticityFlag = 5; break; }else{
						 * TokenReceiverLogger.debug("Previous sender's sign also authenticated.");
						 * TokenReceiverLogger.debug("Token chain "+wholeTokenChains.getString(i)+
						 * "verified"); }
						 * 
						 * } else {
						 * 
						 * //If private key signature verification of prev sender doesn't complete.
						 * chainAutheticityFlag = 4; break; }
						 */

					} else {

						// If hash values dont match
						chainAutheticityFlag = 2;
						break;
					}

				} else {
					chainAutheticityFlag2 = 1;
				}

			}

			// Returning responses after chain verification

			if (chainAutheticityFlag2 == 1) {

				TokenReceiverLogger.debug(
						"Token Transfer for a particular token is continuing without TokenChain authentication check. (Bootstrap purpose)");
				// if condition needs to be removed after bootstrapping.
			}

			// If hash values for tokenchain authenticity checks have a mismatch.
			if (chainAutheticityFlag2 == 2) {
				APIResponse.put("did", senderDidIpfsHash);
				APIResponse.put("tid", "null");
				APIResponse.put("status", "Failed");
				APIResponse.put("message", "Part Token Chain could not be verified. Incorrect hash");
				TokenReceiverLogger.info("Part Token Chain could not be verified");

				output.println("431");
				IPFSNetwork.executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
				output.close();
				input.close();
				sk.close();
				ss.close();
				return APIResponse.toString();
			}

			// If sender tries to edit the token chain with the hash and the sign. Ideally
			// by putting his DID instead of prev sender's DID in the last appended block.
			if (chainAutheticityFlag2 == 3) {
				APIResponse.put("did", senderDidIpfsHash);
				APIResponse.put("tid", "null");
				APIResponse.put("status", "Failed");
				APIResponse.put("message", "Part Token Chain could not be verified. Malicious activity by sender");
				TokenReceiverLogger.info("Part Token Chain could not be verified. Malicious activity by sender");

				output.println("431");
				IPFSNetwork.executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
				output.close();
				input.close();
				sk.close();
				ss.close();
				return APIResponse.toString();
			}

			if (chainAutheticityFlag2 == 4) {
				APIResponse.put("did", senderDidIpfsHash);
				APIResponse.put("tid", "null");
				APIResponse.put("status", "Failed");
				APIResponse.put("message",
						"Part Token Chain could not be verified. Previous sender's signature not verified");
				TokenReceiverLogger
						.info("Part Token Chain could not be verified. Previous sender's signature not verified");

				output.println("431");
				IPFSNetwork.executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
				output.close();
				input.close();
				sk.close();
				ss.close();
				return APIResponse.toString();
			}

			if (chainAutheticityFlag2 == 5) {
				APIResponse.put("did", senderDidIpfsHash);
				APIResponse.put("tid", "null");
				APIResponse.put("status", "Failed");
				APIResponse.put("message",
						"Part Token Chain could not be verified. Previous sender's signature not verified");
				TokenReceiverLogger
						.info("Part Token Chain could not be verified. Previous sender's signature not verified");

				output.println("431");
				IPFSNetwork.executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
				output.close();
				input.close();
				sk.close();
				ss.close();
				return APIResponse.toString();
			}

			if (flag2) {
				TokenReceiverLogger.debug("Part Token Chains verified !");
			}

			// Token Chain authenticity check ends.

			JSONArray partTokenChainContent = new JSONArray();
			JSONArray partTokenContent = new JSONArray();

			for (int i = 0; i < partTokenChains.length(); i++) {

				partTokenChainContent.put(partTokenChains.getJSONArray(partTokens.getString(i)));
				String TokenContent = get(partTokens.getString(i), ipfs).trim();
				partTokenContent.put(TokenContent);
			}

			/*
			 * boolean chainFlag = true; for (int i = 0; i < partTokenChainContent.length();
			 * i++) { JSONArray tokenChainContent = partTokenChainContent.getJSONArray(i);
			 * for (int j = 0; j < tokenChainContent.length(); j++) { String previousHash =
			 * tokenChainContent.getJSONObject(j).getString("previousHash"); String nextHash
			 * = tokenChainContent.getJSONObject(j).getString("nextHash"); String
			 * rePreviousHash, reNextHash; if (tokenChainContent.length() > 1) { if (j == 0)
			 * { rePreviousHash = ""; String rePrev = calculateHash(new
			 * JSONObject().toString(), "SHA3-256"); reNextHash =
			 * calculateHash(tokenChainContent.getJSONObject(j + 1).getString("tid"),
			 * "SHA3-256");
			 * 
			 * if (!((rePreviousHash.equals(previousHash) || rePrev.equals(previousHash)) &&
			 * reNextHash.equals(nextHash))) { chainFlag = false; }
			 * 
			 * } else if (j == tokenChainContent.length() - 1) { rePreviousHash =
			 * calculateHash(tokenChainContent.getJSONObject(j - 1).getString("tid"),
			 * "SHA3-256"); reNextHash = "";
			 * 
			 * if (!(rePreviousHash.equals(previousHash) && reNextHash.equals(nextHash))) {
			 * chainFlag = false; }
			 * 
			 * } else { rePreviousHash = calculateHash(tokenChainContent.getJSONObject(j -
			 * 1).getString("tid"), "SHA3-256"); reNextHash =
			 * calculateHash(tokenChainContent.getJSONObject(j + 1).getString("tid"),
			 * "SHA3-256");
			 * 
			 * if (!(rePreviousHash.equals(previousHash) && reNextHash.equals(nextHash))) {
			 * chainFlag = false; } } } } }
			 * 
			 * if (!chainFlag) { String errorMessage = "Broken Cheque Chain";
			 * output.println("423"); APIResponse.put("did", senderDidIpfsHash);
			 * APIResponse.put("tid", "null"); APIResponse.put("status", "Failed");
			 * APIResponse.put("message", errorMessage);
			 * TokenReceiverLogger.debug(errorMessage);
			 * executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
			 * output.close(); input.close(); sk.close(); ss.close(); return
			 * APIResponse.toString(); }
			 */

//			boolean ownerCheck = true;

			JSONArray allTokens = new JSONArray();
			for (int i = 0; i < wholeTokens.length(); i++)
				allTokens.put(wholeTokens.getString(i));
			for (int i = 0; i < partTokens.length(); i++)
				allTokens.put(partTokens.getString(i));

			JSONArray allTokensChains = new JSONArray();
			for (int i = 0; i < wholeTokenChainContent.size(); i++)
				allTokensChains.put(wholeTokenChainContent.get(i));
			for (int i = 0; i < partTokenChainContent.length(); i++)
				allTokensChains.put(partTokenChainContent.get(i));

			//TokenReceiverLogger.debug("allTokenChain is " + allTokensChains.toString());

			JSONArray invalidTokens = new JSONArray();

			// TODO
			for (int count = 0; count < wholeTokens.length(); count++) {
				String tokens = null;
				//TokenReceiverLogger.debug("Json array tokenChain value is " + wholeTokens.get(count).toString());
				JSONArray tokenChain = new JSONArray(allTokensChains.get(count).toString());
				//TokenReceiverLogger.debug("tokenchain is " + tokenChain);
				//TokenReceiverLogger.debug("tokenchain size is " + tokenChain.length());

				String tokenContent = get(wholeTokens.getString(count), ipfs).trim();
				String tokenLevel = tokenContent.substring(0, tokenContent.length() - 64);
				String tokenNumberHash = tokenContent.substring(tokenContent.length() - 64);

				int tokenLevelInt = Integer.parseInt(tokenLevel);
				int tokenLimitForLevel = tokenLimit[tokenLevelInt];
				int tokenLevelValue = (int) Math.pow(2, tokenLevelInt + 2);
				int minumumStakeHeight = tokenLevelValue * 4;

				// ! check quorum signs for previous transaction for the tokenchain to verify
				// ! the ownership of sender for the token
				JSONObject lastObject = new JSONObject();

				if (tokenChain.length() > 0) {

					lastObject = tokenChain.getJSONObject(tokenChain.length() - 1);

				}
				//TokenReceiverLogger.debug("Last Object = " + lastObject.toString());

//				if (tokenChain.length() > 0 && lastObject.has("owner") && !lastObject.has(MiningConstants.STAKED_TOKEN) &&
//							( (tokenLevelInt == 4 && (tokenDetailMap.get(tokenNumberHash) >= 1204400)) || (tokenLevelInt >= 5)) ) {
				if (tokenChain.length() > 0 && !lastObject.has(MiningConstants.STAKED_TOKEN)
						&& ((tokenLevelInt == 4 && (tokenDetailMap.get(tokenNumberHash) >= 1204400))
								|| (tokenLevelInt >= 5))) {

//						TokenReceiverLogger.debug("Checking ownership");
//						String owner = lastObject.getString("owner");
//						tokens = allTokens.getString(count);
//						String hashString = tokens.concat(senderDidIpfsHash);
//						String hashForPositions = calculateHash(hashString, "SHA3-256");
//						String ownerIdentity = hashForPositions.concat(positionsArray.getString(count));
//						String ownerRecalculated = calculateHash(ownerIdentity, "SHA3-256");
//
//						TokenReceiverLogger.debug("Ownership Here Sender Calculation");
//						TokenReceiverLogger.debug("tokens: " + tokens);
//						TokenReceiverLogger.debug("hashString: " + hashString);
//						TokenReceiverLogger.debug("hashForPositions: " + hashForPositions);
//						TokenReceiverLogger.debug("p1: " + positionsArray.getString(count));
//						TokenReceiverLogger.debug("ownerIdentity: " + ownerIdentity);
//						TokenReceiverLogger.debug("ownerIdentityHash: " + ownerRecalculated);
//
//						if (!owner.equals(ownerRecalculated)) {
//							ownerCheck = false;
//							invalidTokens.put(tokens);
//						}

//						if (ownerCheck && (tokenChain.length() < minumumStakeHeight)) {
					if (tokenChain.length() < minumumStakeHeight) {
						// && (tokenNumber > 1204400)
						if (tokenChain.length() > 0) {
							JSONObject genesiObject = tokenChain.getJSONObject(0);
							JSONArray stakeDataArray = genesiObject.getJSONArray(MiningConstants.MINE_ID);

							/* int randomNumber = new Random().nextInt(15);
							JSONObject genesisSignaturesContent = genesiObject
									.getJSONObject(MiningConstants.QUORUM_SIGN_CONTENT);
							Iterator randomKey = genesisSignaturesContent.keys();
							for (int i = 0; i < randomNumber; i++) {
								randomKey.next();
							} */
							/**
							 * String randomKeyString = randomKey.next().toString(); JSONObject
							 * verificationPick = new JSONObject(); verificationPick.put("did",
							 * randomKeyString); verificationPick.put("hash",
							 * genesiObject.getString("tid")); verificationPick.put("signature",
							 * genesisSignaturesContent.getString(randomKeyString));
							 *
							 * if (verificationPick.getString("hash").equals(genesiObject.getString("tid")))
							 * {
							 *
							 * if (Authenticate.verifySignature(verificationPick.toString())) {
							 * TokenReceiverLogger.debug("Staking check (3) successful"); } else {
							 * TokenReceiverLogger.debug( "Staking check (3) failed: Could not verify
							 * genesis credit signature"); ownerCheck = false; invalidTokens.put(tokens); }
							 * } else { TokenReceiverLogger.debug( "Staking check (3) failed: Genesis TID is
							 * not equal to the hash of the genesis signature"); ownerCheck = false;
							 * invalidTokens.put(tokens); }
							 */
							// else {
							// TokenReceiverLogger.debug("Staking check (3) failed: Genesis Signature not
							// found");
							// ownerCheck = false;
							// invalidTokens.put(tokens);
							// }

							/** Modifying the changes to accomodate 1/5 staking; */
							if (stakeDataArray.length() > 0 && stakeDataArray.length() <= 3) {

								/*
								 * if(stakeDataArray.length() > 0) { JSONObject oneOfThreeStake =
								 * stakeDataArray.getJSONObject(0); JSONObject twoOfThreeStake =
								 * stakeDataArray.getJSONObject(1); JSONObject threeOfThreeStake =
								 * stakeDataArray.getJSONObject(2);
								 * 
								 * String[] stakedTokenTC = new String[3]; String[] stakedTokenSignTC = new
								 * String[3]; String[] stakerDIDTC = new String[3]; String[] mineIDTC = new
								 * String[3]; String[] mineIDSignTC = new String[3];
								 * 
								 * stakedTokenTC[0] = oneOfThreeStake.getString(MiningConstants.STAKED_TOKEN);
								 * stakedTokenSignTC[0] =
								 * oneOfThreeStake.getString(MiningConstants.STAKED_TOKEN_SIGN); stakerDIDTC[0]
								 * = oneOfThreeStake.getString(MiningConstants.STAKED_QUORUM_DID); mineIDTC[0] =
								 * oneOfThreeStake.getString(MiningConstants.MINE_ID); mineIDSignTC[0] =
								 * oneOfThreeStake.getString(MiningConstants.MINE_ID_SIGN);
								 * 
								 * stakedTokenTC[1] = twoOfThreeStake.getString(MiningConstants.STAKED_TOKEN);
								 * stakedTokenSignTC[1] =
								 * twoOfThreeStake.getString(MiningConstants.STAKED_TOKEN_SIGN); stakerDIDTC[1]
								 * = twoOfThreeStake.getString(MiningConstants.STAKED_QUORUM_DID); mineIDTC[1] =
								 * twoOfThreeStake.getString(MiningConstants.MINE_ID); mineIDSignTC[1] =
								 * twoOfThreeStake.getString(MiningConstants.MINE_ID_SIGN);
								 * 
								 * stakedTokenTC[2] = threeOfThreeStake.getString(MiningConstants.STAKED_TOKEN);
								 * stakedTokenSignTC[2] =
								 * threeOfThreeStake.getString(MiningConstants.STAKED_TOKEN_SIGN);
								 * stakerDIDTC[2] =
								 * threeOfThreeStake.getString(MiningConstants.STAKED_QUORUM_DID); mineIDTC[2] =
								 * threeOfThreeStake.getString(MiningConstants.MINE_ID); mineIDSignTC[2] =
								 * threeOfThreeStake.getString(MiningConstants.MINE_ID_SIGN);
								 */

								String[] stakedTokenTC = new String[stakeDataArray.length()];
								String[] stakedTokenSignTC = new String[stakeDataArray.length()];
								String[] stakerDIDTC = new String[stakeDataArray.length()];
								String[] mineIDTC = new String[stakeDataArray.length()];
								String[] mineIDSignTC = new String[stakeDataArray.length()];

								for (int i = 0; i < stakeDataArray.length(); i++) {

									JSONObject oneOfThreeStake = stakeDataArray.getJSONObject(i);

									stakedTokenTC[i] = oneOfThreeStake.getString(MiningConstants.STAKED_TOKEN);
									stakedTokenSignTC[i] = oneOfThreeStake.getString(MiningConstants.STAKED_TOKEN_SIGN);
									stakerDIDTC[i] = oneOfThreeStake.getString(MiningConstants.STAKED_QUORUM_DID);
									mineIDTC[i] = oneOfThreeStake.getString(MiningConstants.MINE_ID);
									mineIDSignTC[i] = oneOfThreeStake.getString(MiningConstants.MINE_ID_SIGN);
								}

								TokenReceiverLogger.debug("mineIDTC length is " + mineIDTC.length);

								for (int stakeCount = 0; stakeCount < mineIDTC.length; stakeCount++) {

									String mineIDContent = get(mineIDTC[stakeCount], ipfs);
									JSONObject mineIDContentJSON = new JSONObject(mineIDContent);
									TokenReceiverLogger.debug(mineIDContentJSON.toString());

									JSONObject stakeData = mineIDContentJSON.getJSONObject(MiningConstants.STAKE_DATA);

									String stakerDIDMineData = stakeData.getString(MiningConstants.STAKED_QUORUM_DID);
									String stakedTokenMineData = stakeData.getString(MiningConstants.STAKED_TOKEN);
									String stakedTokenSignMineData = stakeData
											.getString(MiningConstants.STAKED_TOKEN_SIGN);

									TokenReceiverLogger.debug(stakerDIDTC[stakeCount]);
									TokenReceiverLogger.debug(stakedTokenTC[stakeCount]);
									TokenReceiverLogger.debug(stakedTokenSignTC[stakeCount]);

									TokenReceiverLogger.debug(stakerDIDMineData);
									TokenReceiverLogger.debug(stakedTokenMineData);
									TokenReceiverLogger.debug(stakedTokenSignMineData);

									if (stakerDIDTC[stakeCount].equals(stakerDIDMineData)
											&& stakedTokenTC[stakeCount].equals(stakedTokenMineData)
											&& stakedTokenSignTC[stakeCount].equals(stakedTokenSignMineData)) {

										TokenReceiverLogger.debug("array n non array data are same");

										JSONObject detailsToVerify = new JSONObject();
										detailsToVerify.put("did", stakerDIDTC[stakeCount]);
										detailsToVerify.put("hash", mineIDTC[stakeCount]);
										detailsToVerify.put("signature", mineIDSignTC[stakeCount]);
										TokenReceiverLogger.debug("detailsToVerify - " + detailsToVerify.toString());

										if (Authenticate.verifySignature(detailsToVerify.toString())) {

											boolean minedTokenStatus = true;
											ArrayList<String> ownersArray = IPFSNetwork
													.dhtOwnerCheck(stakedTokenTC[stakeCount]);
											TokenReceiverLogger.debug("dht owner are " + ownersArray.toString()
													+ " size is " + ownersArray.size());
											for (int i = 0; i < ownersArray.size(); i++) {
												if (ownersArray.get(i).equals(stakerDIDTC[stakeCount])) {
													minedTokenStatus = false;
												}
											}
											if (!minedTokenStatus) {
												TokenReceiverLogger.debug("Staked token is not found with staker DID: "
														+ stakerDIDTC[stakeCount]);
//														ownerCheck = false;
												invalidTokens.put(tokens);
											}

										} else {
											TokenReceiverLogger.debug(
													"Staking check (2) failed - unable to verify mine ID signature by staker: "
															+ stakerDIDTC[stakeCount]);
//													ownerCheck = false;
											invalidTokens.put(tokens);
										}

										TokenReceiverLogger.debug("MineID Verification Successful with Staking node: "
												+ stakerDIDTC[stakeCount]);
									} else {
										TokenReceiverLogger.debug("Staking check (2) failed");
//												ownerCheck = false;
										invalidTokens.put(tokens);
									}

									TokenReceiverLogger.debug("Staking check (2) successful for count " + stakeCount);
									// } else {
									// TokenReceiverLogger.debug(
									// "Staking check (2) failed: Could not verify mine ID signature");
									// ownerCheck = false;
									// invalidTokens.put(tokens);
									// }
								}
								// }

							} else {
//									ownerCheck = false;
								TokenReceiverLogger.debug("Staked Token is not available!");

							}
						}

					}
				} else if (tokenChain.length() > 0 && lastObject.has(MiningConstants.STAKED_TOKEN)) {

					Boolean minedTokenStatus = true;

					String mineID = lastObject.getString(MiningConstants.MINE_ID);

					String mineIDContent = get(mineID, ipfs);
					JSONObject mineIDContentJSON = new JSONObject(mineIDContent);

					JSONObject stakeData = mineIDContentJSON.getJSONObject(MiningConstants.STAKE_DATA);

					ArrayList<String> ownersArray = IPFSNetwork
							.dhtOwnerCheck(stakeData.getString(MiningConstants.STAKED_TOKEN));
					for (int i = 0; i < ownersArray.size(); i++) {
						if (!VerifyStakedToken.Contact(ownersArray.get(i), SEND_PORT + 16,
								stakeData.getString(MiningConstants.STAKED_TOKEN),
								mineIDContentJSON.getString("tokenContent"), "Get-TokenChain-Height")) {
							minedTokenStatus = false;
						}
					}
					if (!minedTokenStatus) {
						TokenReceiverLogger.debug("Staking check failed: Found staked token but token height < 46");
//							ownerCheck = false;
						invalidTokens.put(tokens);
					}

					TokenReceiverLogger.debug(
							"Staking check failed: Found staked token but unable to transfer while mined token height is not satisfied for the network");
//						ownerCheck = false;
					invalidTokens.put(tokens);

					// JSONObject tokenToVerify = new JSONObject();
					// if (mineIDContentJSON.has(MiningConstants.STAKE_DATA)) {

					// JSONObject stakeData =
					// mineIDContentJSON.getJSONObject(MiningConstants.STAKE_DATA);
					// String stakerDID = stakeData.getString(STAKED_QUORUM_DID);
					// String stakedToken = stakeData.getString(STAKED_TOKEN);
					// String stakedTokenSign = stakeData.getString(STAKED_TOKEN_SIGN);

					// tokenToVerify.put("did", senderDidIpfsHash);
					// tokenToVerify.put("hash", stakedToken);
					// tokenToVerify.put("signature", stakedTokenSign);

					// if (Authenticate.verifySignature(tokenToVerify.toString())) {

					// ArrayList<String> ownersArray = IPFSNetwork.dhtOwnerCheck(stakedToken);
					// for (int i = 0; i < ownersArray.size(); i++) {
					// if (!VerifyStakedToken.Contact(ownersArray.get(i), SEND_PORT + 16,
					// mineIDContentJSON.getString("tokenContent"))) {
					// minedTokenStatus = false;
					// }
					// }
					// if (!minedTokenStatus) {
					// TokenReceiverLogger
					// .debug("Staking check failed: Found staked token but token height < 46");
					// ownerCheck = false;
					// invalidTokens.put(tokens);
					// }

					// TokenReceiverLogger.debug(
					// "Staking check failed: Found staked token but unable to transfer while mined
					// token height is not satisfied for the network");
					// ownerCheck = false;
					// invalidTokens.put(tokens);

					// } else {
					// TokenReceiverLogger.debug(
					// "Staking check failed: Found staked token but unable to verify staked token
					// height");
					// ownerCheck = false;
					// invalidTokens.put(tokens);
					// }
					// }

				}

			}

//			if (!ownerCheck) {
//				TokenReceiverLogger.debug("Ownership Check Failed");
//				String errorMessage = "Ownership Check Failed";
//				output.println("424");
//				output.println(invalidTokens.toString());
//				APIResponse.put("did", senderDidIpfsHash);
//				APIResponse.put("tid", "null");
//				APIResponse.put("status", "Failed");
//				APIResponse.put("message", errorMessage);
//				TokenReceiverLogger.debug(errorMessage);
//				executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
//				output.close();
//				input.close();
//				sk.close();
//				ss.close();
//				return APIResponse.toString();
//			} else
//				TokenReceiverLogger.debug("Ownership Check Passed");

			// -------------------------------------------------------

			boolean partsAvailable = true;
			for (int i = 0; i < partTokenChainContent.length(); i++) {
				Double senderCount = 0.000D, receiverCount = 0.000D;
				JSONArray tokenChainContent = partTokenChainContent.getJSONArray(i);
				for (int k = 0; k < tokenChainContent.length(); k++) {
					if (tokenChainContent.getJSONObject(k).has("role")) {
						if (tokenChainContent.getJSONObject(k).getString("role").equals("Sender")
								&& tokenChainContent.getJSONObject(k).getString("sender").equals(senderDidIpfsHash)) {
							senderCount += tokenChainContent.getJSONObject(k).getDouble("amount");
						} else if (tokenChainContent.getJSONObject(k).getString("role").equals("Receiver")
								&& tokenChainContent.getJSONObject(k).getString("receiver").equals(senderDidIpfsHash)) {
							receiverCount += tokenChainContent.getJSONObject(k).getDouble("amount");
						}
					}
				}
				FunctionsLogger.debug("Sender Parts: " + formatAmount(senderCount));
				FunctionsLogger.debug("Receiver Parts: " + formatAmount(receiverCount));
				Double availableParts = receiverCount - senderCount;

				availableParts = formatAmount(availableParts);
				availableParts += amountLedger.getDouble(partTokens.getString(i));
				availableParts = formatAmount(availableParts);

				if (availableParts > 1.000D) {
					//TokenReceiverLogger.debug("Token wholly spent: " + partTokens.getString(i));
					TokenReceiverLogger.debug("Parts: " + availableParts);
					partsAvailable = false;
				}
			}
			if (!partsAvailable) {
				String errorMessage = "Token wholly spent already";
				output.println("425");
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
			output.println("200");

			String senderDetails;
			try {
				senderDetails = input.readLine();
			} catch (SocketException e) {
				TokenReceiverLogger.warn("Sender Stream Null - Sender Details");
				APIResponse.put("did", "");
				APIResponse.put("tid", "null");
				APIResponse.put("status", "Failed");
				APIResponse.put("message", "Sender Stream Null - Sender Details");

				output.close();
				input.close();
				sk.close();
				ss.close();
				return APIResponse.toString();

			}
			JSONObject SenderDetails = new JSONObject(senderDetails);

			// String senderSignature = SenderDetails.getString("pvtKeySign");
			String senderPvtShareBits = SenderDetails.getString("pvtShareBits");

			String tid = SenderDetails.getString("tid");
			String comment = SenderDetails.getString("comment");
			String Status = SenderDetails.getString("status");
			String QuorumDetails = SenderDetails.getString("quorumsign");
			JSONArray pledgedDetails = SenderDetails.getJSONArray("pledgeDetails");
			//TokenReceiverLogger.debug("pledged details is " + pledgedDetails);
			String BlockHash = new String();
			if (SenderDetails.toString().contains("blockHash")) {
				BlockHash = SenderDetails.getString("blockHash");
			}

			BufferedImage senderWidImage = ImageIO.read(new File(DATA_PATH + senderDidIpfsHash + "/PublicShare.png"));
			SenWalletBin = PropImage.img2bin(senderWidImage);

			JSONArray wholeTokenChainHash = new JSONArray();
			for (int i = 0; i < intPart; i++)
				wholeTokenChainHash.put(wholeTokenChains.getString(i));

			String hash = calculateHash(
					wholeTokens.toString() + wholeTokenChainHash.toString() + partTokens.toString()
							+ partTokenChainsHash.toString() + receiverDidIpfsHash + senderDidIpfsHash + comment,
					"SHA3-256");

			TokenReceiverLogger.debug("hash is " + hash);

			TokenReceiverLogger.debug("Verifying Quorum ...  ");
			TokenReceiverLogger.debug("Please wait, this might take a few seconds");

			if (!Status.equals("Consensus Failed")) {
				boolean yesQuorum = false;
				if (Status.equals("Consensus Reached")) {
					quorumSignatures = new JSONArray(QuorumDetails);

					// String selectQuorumHash = calculateHash(senderToken, "SHA3-256");
					String verifyQuorumHash = hash;
					TokenReceiverLogger.debug("verifyQuorumHash is " + verifyQuorumHash);

					String quorumHash = calculateHash(verifyQuorumHash.concat(receiverDidIpfsHash), "SHA3-256");
					TokenReceiverLogger.debug("quorumHash is " + quorumHash);
					TokenReceiverLogger.debug("quorumHash is (verifyQuorumHash)concat(receiverDidIpfsHash)");

					/*
					 * Iterator<String> keys = quorumSignatures.keys(); while (keys.hasNext()) {
					 * String key = keys.next(); quorumDID.add(key); }
					 * 
					 * for (String quorumDidIpfsHash : quorumDID) { syncDataTable(quorumDidIpfsHash,
					 * null); String quorumWidIpfsHash = getValues(DATA_PATH + "DataTable.json",
					 * "walletHash", "didHash", quorumDidIpfsHash);
					 * 
					 * nodeData(quorumDidIpfsHash, quorumWidIpfsHash, ipfs); }
					 */

					for (int i = 0; i < quorumSignatures.length(); i++) {

						JSONObject QuorumMember = quorumSignatures.getJSONObject(i);
						syncDataTable(QuorumMember.getString("quorum_did"), null);
						String quorumWidIpfsHash = getValues(DATA_PATH + "DataTable.json", "walletHash", "didHash",
								QuorumMember.getString("quorum_did"));
						String quorumPid = getValues(DATA_PATH + "DataTable.json", "peerid", "didHash",
								QuorumMember.getString("quorum_did"));
						bootstrapConnect(quorumPid, ipfs);

						nodeData((QuorumMember.getString("quorum_did")), quorumWidIpfsHash, ipfs);
					}

					for (int i = 0; i < quorumSignatures.length(); i++) {

						JSONObject quorumMember = quorumSignatures.getJSONObject(i);

						// TokenReceiverLogger.debug("Quorum sign of member " +i+ ":"+quorumMember);

						String quorumsPrivateShareSign = quorumMember.getString("quorumPrivateShareSign");
						String quorumsPrivateKeySign = quorumMember.getString("quorumPvtKeySign");

						String QuorumPublicKeyIpfsHash = getPubKeyIpfsHash_DIDserver(
								quorumMember.getString("quorum_did"), 2); // get public key ipfs hash of the quorum
																			// member.
						
						//TODO
						if(QuorumPublicKeyIpfsHash.isEmpty()) {
							output.println("432");
							APIResponse.put("did", senderDidIpfsHash);
							APIResponse.put("tid", tid);
							APIResponse.put("status", "Failed");
							APIResponse.put("message", "Quorum data not available for "+quorumMember.getString("quorum_did"));
							TokenReceiverLogger.info("Quorum data not available for "+quorumMember.getString("quorum_did")+". Please initate sync command");
							IPFSNetwork.executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
							output.close();
							input.close();
							sk.close();
							ss.close();
							return APIResponse.toString();
							
						}
						
						String quorumPubKeyStr = IPFSNetwork.get(QuorumPublicKeyIpfsHash, ipfs); // get quorum member's
																									// public key from
																									// ipfs.
						String pubKeyAlgo = publicKeyAlgStr(quorumPubKeyStr);

						JSONObject detailsForVerify = new JSONObject();
						detailsForVerify.put("did", quorumMember.getString("quorum_did"));
						detailsForVerify.put("hash", quorumHash);
						detailsForVerify.put("signature", quorumMember.getString("quorumPrivateShareSign"));

						boolean val = ((verifySignature(quorumsPrivateShareSign,
								getPubKeyFromStr(quorumPubKeyStr, pubKeyAlgo), quorumsPrivateKeySign, pubKeyAlgo))
								&& (Authenticate.verifySignature(detailsForVerify.toString())));
						if (val) {
							quorumSignVerifyCount++;
							TokenReceiverLogger
									.debug("Quorum member " + quorumMember.getString("quorum_did") + " verified.");
						}
					}
					TokenReceiverLogger.debug("Verified Quorum Count " + quorumSignVerifyCount);
					yesQuorum = quorumSignVerifyCount >= quorumSignatures.length();
				}

				TokenReceiverLogger.debug("Hash to verify Sender: " + hash);
				JSONObject detailsForVerify = new JSONObject();
				detailsForVerify.put("did", senderDidIpfsHash);
				detailsForVerify.put("hash", hash);
				detailsForVerify.put("signature", senderPvtShareBits);

				boolean yesSender = false;

				String senderPublicKeyIpfsHash = getPubKeyIpfsHash_DIDserver(senderDidIpfsHash, 1); // get public key
																									// ipfs hash of the
																									// sender.
				
				if(senderPublicKeyIpfsHash.isEmpty()) {
					output.println("433");
					APIResponse.put("did", senderDidIpfsHash);
					APIResponse.put("tid", tid);
					APIResponse.put("status", "Failed");
					APIResponse.put("message", "Sender data not available for "+senderDidIpfsHash);
					TokenReceiverLogger.info("Sender's public key data not available for "+senderDidIpfsHash+". Please initate sync command");
					IPFSNetwork.executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
					output.close();
					input.close();
					sk.close();
					ss.close();
					return APIResponse.toString();
					
				}
				

				String senderPubKeyStr = IPFSNetwork.get(senderPublicKeyIpfsHash, ipfs); // get sender's public key from
																							// ipfs.

				String pubKeyAlgo = publicKeyAlgStr(senderPubKeyStr);

				// verifySignature uses sender's public key to verify the private key signature.
				// if(verifySignature(senderPvtShareBits,getPubKeyFromStr(senderPubKeyStr,pubKeyAlgo),senderSignature,pubKeyAlgo)==true)

				// {
				yesSender = Authenticate.verifySignature(detailsForVerify.toString());
				// }

				TokenReceiverLogger.debug("Quorum Auth : " + yesQuorum + " Sender Auth : " + yesSender);
				if (!(yesSender && yesQuorum)) {
					output.println("420");
					APIResponse.put("did", senderDidIpfsHash);
					APIResponse.put("tid", tid);
					APIResponse.put("status", "Failed");
					APIResponse.put("message", "Sender / Quorum not verified");
					TokenReceiverLogger.info("Sender / Quorum not verified");
					IPFSNetwork.executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
					output.close();
					input.close();
					sk.close();
					ss.close();
					return APIResponse.toString();
				}

				repo(ipfs);
				TokenReceiverLogger.debug("Sender and Quorum Verified");
				output.println("200");

				// To accecpt sender req for new Token chain block, and provide the same
				String request;
				try {
					request = input.readLine();
				} catch (SocketException e) {
					TokenReceiverLogger.warn(
							"Sender Stream Null, receiver unable to accept senders request to get new Token chain block for hashing");
					APIResponse.put("did", "");
					APIResponse.put("tid", "null");
					APIResponse.put("status", "Failed");
					APIResponse.put("message",
							"Sender Stream Null, receiver unable to accept senders request to get new Token chain block for hashing");

					output.close();
					input.close();
					sk.close();
					ss.close();
					return APIResponse.toString();

				}

				// JSONArray arrLastObjects = new JSONArray();
				JSONObject lastObject = new JSONObject();
				//TokenReceiverLogger.debug("%%%%% WholeTokenChainsWithAppendedBlock before lastobj"+ WholeTokenChainsWithAppendedBlock.toString());

				JSONArray lastObjectArray = new JSONArray();
				if (request.equals("Request for new blocks being added to the Token Chains")) {

					TokenReceiverLogger.debug("Request for new blocks being added to the Token Chains");

					// TODO
					TokenReceiverLogger.debug("intPart: " + intPart);
					for (int i = 0; i < intPart; i++) {

						ArrayList<String> groupTokens = new ArrayList<>();
						for (int k = 0; k < intPart; k++) {
							if (!wholeTokens.getString(i).equals(wholeTokens.getString(k)))
								groupTokens.add(wholeTokens.getString(k));
						}

						JSONArray arrToken = new JSONArray();
						JSONObject objectToken = new JSONObject();
						objectToken.put("tokenHash", wholeTokens.getString(i));
						arrToken.put(objectToken);

						JSONArray arr = new JSONArray(wholeTokenChainContent.get(i));
						//TokenReceiverLogger.debug("%%%%% Current tokenchain: " + arr);

						// lastObject.put("senderSign", senderPvtShareBits);
						lastObject.put("sender", senderDidIpfsHash);
						lastObject.put("group", allTokens);
						lastObject.put("comment", comment);
						lastObject.put("tid", tid);
						// lastObject.put("owner", ownerIdentityHash);
						lastObject.put("receiver", receiverDidIpfsHash);
						lastObject.put("pledgeToken", "");
						lastObject.put("tokensPledgedFor", allTokens);
						lastObject.put("tokensPledgedWith", pledgedDetails);
						lastObject.put("distributedObject", distributedObject);


						if (!BlockHash.isEmpty()) {
							lastObject.put("blockHash", BlockHash);
						}

						arr.put(lastObject);
						//TokenReceiverLogger.debug("%%%%% Last BLock added tokenchain: " + arr);

						WholeTokenChainsWithAppendedBlock.add(arr.toString());

						JSONObject tokenLastObject = new JSONObject();
						tokenLastObject.put("token", wholeTokens.getString(i));
						tokenLastObject.put("lastObject", lastObject);
						lastObjectArray.put(tokenLastObject);
					}

				}
				//TokenReceiverLogger.debug("Last object for sender is " + lastObjectArray.toString());
				output.println(lastObjectArray.toString());

				String hashAndSignsforTokenChains;
				try {
					hashAndSignsforTokenChains = input.readLine();

					//TokenReceiverLogger.info("!!!" + hashAndSignsforTokenChains);

					if (hashAndSignsforTokenChains.contains("Token chains Not Matching")) {
						TokenReceiverLogger.warn("Token chains Not Matching");
						APIResponse.put("did", "");
						APIResponse.put("tid", "null");
						APIResponse.put("status", "Failed");
						APIResponse.put("message", "Token chains Not Matching");

						output.close();
						input.close();
						sk.close();
						ss.close();
						return APIResponse.toString();
					}

					hash_Signs_ForTokenChains = new JSONArray(hashAndSignsforTokenChains);
					//TokenReceiverLogger.debug("%%%%% hash_Signs_ForTokenChains: " + hash_Signs_ForTokenChains);

				} catch (SocketException e) {
					TokenReceiverLogger.warn("Sender Stream Null - Token Chain Updation status");
					APIResponse.put("did", "");
					APIResponse.put("tid", "null");
					APIResponse.put("status", "Failed");
					APIResponse.put("message", "Sender Stream Null - Token Chain Updation Status");

					output.close();
					input.close();
					sk.close();
					ss.close();
					return APIResponse.toString();

				}

//				//To accecpt sender req for new Part Token chain blocks, and provide the same
//                String request_parttokenchains;
//                try {
//                    request_parttokenchains = input.readLine();
//                } catch (SocketException e) {
//                    TokenReceiverLogger.warn("Sender Stream Null, receiver unable to accept senders request to get new part Token chain block for hashing");
//                    APIResponse.put("did", "");
//                    APIResponse.put("tid", "null");
//                    APIResponse.put("status", "Failed");
//                    APIResponse.put("message", "Sender Stream Null, receiver unable to accept senders request to get new part Token chain block for hashing");
//
//                    output.close();
//                    input.close();
//                    sk.close();
//                    ss.close();
//                    return APIResponse.toString();
//
//                }

				JSONObject parttokenchainsToBeHashed = new JSONObject();
//                if(request_parttokenchains.equals("Request for Part Token Chains to be hashed")){

				// TODO
				for (int i = 0; i < partTokens.length(); i++) {
					JSONObject chequeObject = new JSONObject();
					chequeObject.put("sender", senderDidIpfsHash);
					chequeObject.put("receiver", receiverDidIpfsHash);
					chequeObject.put("parent-token", partTokens.getString(i));
					chequeObject.put("parent-chain", partTokenChains.getJSONArray(partTokens.getString(i)));
					Double partAmount = formatAmount(amountLedger.getDouble(partTokens.getString(i)));
					chequeObject.put("amount", partAmount);
					chequeObject.put("tid", tid);

					writeToFile(LOGGER_PATH.concat(partTokens.getString(i)), chequeObject.toString(), false);
					String chequeHash = IPFSNetwork.add(LOGGER_PATH.concat(partTokens.getString(i)), ipfs);
					deleteFile(LOGGER_PATH.concat(partTokens.getString(i)));

					String tokens = partTokens.getString(i);
					String hashString = tokens.concat(receiverDidIpfsHash);
					String hashForPositions = calculateHash(hashString, "SHA3-256");

//						BufferedImage pvt1 = ImageIO
//								.read(new File(DATA_PATH.concat(receiverDidIpfsHash).concat("/PrivateShare.png")));
//						String firstPrivate = PropImage.img2bin(pvt1);
//						int[] privateIntegerArray1 = strToIntArray(firstPrivate);
//						String privateBinary = Functions.intArrayToStr(privateIntegerArray1);
//						String positions = "";
//						for (int j = 0; j < privateIntegerArray1.length; j += 49152) {
//							positions += privateBinary.charAt(j);
//						}
//
//						String ownerIdentity = hashForPositions.concat(positions);
//						String ownerIdentityHash = calculateHash(ownerIdentity, "SHA3-256");
//
//						TokenReceiverLogger.debug("Ownership Here");
//						TokenReceiverLogger.debug("tokens: " + partTokens.getString(i));
//						TokenReceiverLogger.debug("hashString: " + hashString);
//						TokenReceiverLogger.debug("hashForPositions: " + hashForPositions);
//						TokenReceiverLogger.debug("p1: " + positions);
//						TokenReceiverLogger.debug("ownerIdentity: " + ownerIdentity);
//						TokenReceiverLogger.debug("ownerIdentityHash: " + ownerIdentityHash);

					JSONObject newPartObject = new JSONObject();
					// newPartObject.put("senderSign", senderSignature);
					newPartObject.put("sender", senderDidIpfsHash);
					newPartObject.put("receiver", receiverDidIpfsHash);
					newPartObject.put("comment", comment);
					newPartObject.put("tid", tid);
					newPartObject.put("pledgeToken", "");
					newPartObject.put("tokensPledgedFor", allTokens);
					newPartObject.put("tokensPledgedWith", pledgedDetails);
					newPartObject.put("distributedObject", distributedObject);
					// newPartObject.put("nextHash", "");
//						newPartObject.put("owner", ownerIdentityHash);
					/*
					 * if (partTokenChainContent.getJSONArray(i).length() == 0)
					 * newPartObject.put("previousHash", ""); else newPartObject.put("previousHash",
					 * calculateHash(partTokenChainContent.getJSONArray(i)
					 * .getJSONObject(partTokenChainContent.getJSONArray(i).length() - 1)
					 * .getString("tid"), "SHA3-256"));
					 */

					newPartObject.put("amount", partAmount);
					newPartObject.put("cheque", chequeHash);
					newPartObject.put("role", "Receiver");

					JSONArray partTokenChainContentArray = partTokenChainContent.getJSONArray(i);

					File chainFile = new File(PART_TOKEN_CHAIN_PATH.concat(partTokens.getString(i)).concat(".json"));
					if (chainFile.exists()) {

						String readChain = readFile(PART_TOKEN_CHAIN_PATH + partTokens.getString(i) + ".json");
						JSONArray readChainArray = new JSONArray(readChain);
						JSONArray chainArray = new JSONArray();
						for (int j = 0; j < readChainArray.length(); j++) {

							JSONObject object = readChainArray.getJSONObject(j);

							if (object.has("nextHash") && object.has("previousHash")) {
								object.remove("nextHash");
								object.remove("previousHash");
							}

							chainArray.put(object);

						}

						chainArray
								.put(partTokenChainContentArray.getJSONObject(partTokenChainContentArray.length() - 1));
						chainArray.put(newPartObject);
						// TokenReceiverLogger.debug("Parts token chain to be hashed :
						// "+readChainArray);

						parttokenchainsToBeHashed.put(partTokens.getString(i), chainArray);
						final_parttokenchains.put(partTokens.getString(i), chainArray);

					} else {

						partTokenChainContentArray.put(newPartObject);

						parttokenchainsToBeHashed.put(partTokens.getString(i), partTokenChainContentArray);
						final_parttokenchains.put(partTokens.getString(i), partTokenChainContentArray);

					}
				}
//				}

//				output.println(parttokenchainsToBeHashed.toString());

//				String req_hashesAndSignsPartTokenChains;
//				try {
//					req_hashesAndSignsPartTokenChains = input.readLine();
//					hashes_Signs_PartTokenChains = new JSONArray(req_hashesAndSignsPartTokenChains);
//
//
//					}catch (SocketException e) {
//						TokenReceiverLogger.warn("Sender Stream Null - Token Chain Updation status");
//						APIResponse.put("did", "");
//						APIResponse.put("tid", "null");
//						APIResponse.put("status", "Failed");
//						APIResponse.put("message", "Sender Stream Null - Token Chain Updation Status");
//
//						output.close();
//						input.close();
//						sk.close();
//						ss.close();
//						return APIResponse.toString();
//
//					}

				String pinDetails;
				try {
					pinDetails = input.readLine();
				} catch (SocketException e) {
					TokenReceiverLogger.warn("Sender Stream Null - Pinning Status");
					APIResponse.put("did", "");
					APIResponse.put("tid", "null");
					APIResponse.put("status", "Failed");
					APIResponse.put("message", "Sender Stream Null - Pinning Status");

					output.close();
					input.close();
					sk.close();
					ss.close();
					return APIResponse.toString();

				}

				if (pinDetails.equals("Unpinned")) {
					int pinCount = 0;
					for (int i = 0; i < intPart; i++) {
						Path tokensPath = Paths.get(TOKENS_PATH + wholeTokens.get(i));
						Files.write(tokensPath, wholeTokenContent.get(i).getBytes());
						add(TOKENS_PATH + wholeTokens.get(i), ipfs);
						pin(wholeTokens.get(i).toString(), ipfs);
						pinCount++;

					}

					for (int i = 0; i < partTokens.length(); i++) {
						File tokenFile = new File(PART_TOKEN_PATH + partTokens.getString(i));
						if (!tokenFile.exists())
							tokenFile.createNewFile();

						writeToFile(PART_TOKEN_PATH + partTokens.getString(i), partTokenContent.getString(i), false);
						String tokenHash = add(PART_TOKEN_PATH + partTokens.getString(i), ipfs);
						pin(tokenHash, ipfs);

					}

					if (pinCount == intPart) {
						TokenReceiverLogger.debug("Pinned All Tokens");
						output.println("Successfully Pinned");

						String essentialShare;
						try {
							essentialShare = input.readLine();
						} catch (SocketException e) {
							TokenReceiverLogger.warn("Sender Stream Null - EShare Details");
							APIResponse.put("did", "");
							APIResponse.put("tid", "null");
							APIResponse.put("status", "Failed");
							APIResponse.put("message", "Sender Stream Null - EShare Details");

							output.close();
							input.close();
							sk.close();
							ss.close();
							return APIResponse.toString();

						}
						long endTime = System.currentTimeMillis();

						// Writing Whole and part token chains to receiver's local storage.
						for (int i = 0; i < intPart; i++) {

							//TokenReceiverLogger.debug("%%%%% WholeTokenChainsWithAppendedBlock: " + WholeTokenChainsWithAppendedBlock);

							String tokenChainContent_WithoutHashAndSign = WholeTokenChainsWithAppendedBlock.get(i);
							JSONArray tokenChain = new JSONArray(tokenChainContent_WithoutHashAndSign);
							//TokenReceiverLogger.debug("%%%%% Initial Tokenchain last object: " + tokenChain);

							JSONObject lastObj = new JSONObject();
							lastObj = tokenChain.getJSONObject(tokenChain.length() - 1);
							tokenChain.remove(tokenChain.length() - 1);
							lastObj.put("hash", hash_Signs_ForTokenChains.getJSONObject(i).getString("hash"));
							lastObj.put("pvtShareBits",
									hash_Signs_ForTokenChains.getJSONObject(i).getString("pvtShareBits"));
							// lastObj.put("pvtKeySign",
							// hash_Signs_ForTokenChains.getJSONObject(i).getString("pvtKeySign"));

							//TokenReceiverLogger.debug("%%%%% Tokenchain last object: " + lastObj);

							tokenChain.put(lastObj);

							//TokenReceiverLogger.debug("%%%%% Writing to file tokenChain: " + tokenChain.toString());
							writeToFile(TOKENCHAIN_PATH + wholeTokens.getString(i) + ".json", tokenChain.toString(),
									false);
						}

						for (int i = 0; i < partTokens.length(); i++) {

							JSONArray finalPartTokenChain = final_parttokenchains.getJSONArray(partTokens.getString(i));

//							JSONObject last = new JSONObject();
//							last = finalPartTokenChain.getJSONObject(finalPartTokenChain.length()-1);
//							finalPartTokenChain.remove(finalPartTokenChain.length()-1);
//
//							last.put("hash",hashes_Signs_PartTokenChains.getJSONObject(i).getString("hash"));
//							last.put("pvtShareBits",hashes_Signs_PartTokenChains.getJSONObject(i).getString("pvtShareBits"));
//							//last.put("pvtKeySign", hashes_Signs_PartTokenChains.getJSONObject(i).getString("pvtKeySign"));
//
//							finalPartTokenChain.put(last);

							writeToFile(PART_TOKEN_CHAIN_PATH + partTokens.getString(i) + ".json",
									finalPartTokenChain.toString(), false);
						}

						JSONObject transactionRecord = new JSONObject();
						transactionRecord.put("role", "Receiver");
						transactionRecord.put("tokens", allTokens);
						transactionRecord.put("txn", tid);
						transactionRecord.put("quorumList", quorumSignatures);
						transactionRecord.put("senderDID", senderDidIpfsHash);
						transactionRecord.put("receiverDID", receiverDidIpfsHash);
						transactionRecord.put("Date", getCurrentUtcTime());
						transactionRecord.put("totalTime", (endTime - startTime));
						transactionRecord.put("comment", comment);
						transactionRecord.put("essentialShare", essentialShare);
						amount = formatAmount(amount);
						transactionRecord.put("amount-received", amount);

						JSONArray transactionHistoryEntry = new JSONArray();
						transactionHistoryEntry.put(transactionRecord);
						updateJSON("add", WALLET_DATA_PATH + "TransactionHistory.json",
								transactionHistoryEntry.toString());

						for (int i = 0; i < wholeTokens.length(); i++) {
							String bankFile = readFile(PAYMENTS_PATH.concat("BNK00.json"));
							JSONArray bankArray = new JSONArray(bankFile);
							JSONObject tokenObject1 = new JSONObject();
							tokenObject1.put("tokenHash", wholeTokens.getString(i));
							bankArray.put(tokenObject1);
							Functions.writeToFile(PAYMENTS_PATH.concat("BNK00.json"), bankArray.toString(), false);
							Path tokensPath = Paths.get(TOKENS_PATH + wholeTokens.get(i));
							Files.write(tokensPath, wholeTokenContent.get(i).getBytes());
						}

						String partsFile = readFile(PAYMENTS_PATH.concat("PartsToken.json"));
						JSONArray partsReadArray = new JSONArray(partsFile);

						for (int i = 0; i < partTokens.length(); i++) {
							boolean writeParts = true;
							for (int j = 0; j < partsReadArray.length(); j++) {
								if (partsReadArray.getJSONObject(j).getString("tokenHash")
										.equals(partTokens.getString(i)))
									writeParts = false;
							}
							if (writeParts) {
								JSONObject partObject = new JSONObject();
								partObject.put("tokenHash", partTokens.getString(i));
								partsReadArray.put(partObject);
							}
						}
						writeToFile(PAYMENTS_PATH.concat("PartsToken.json"), partsReadArray.toString(), false);

						output.println("Send Response");

						TokenReceiverLogger.debug("!@#$% 1");

						// Updating the sender's part token chains with the chains hash and receivers
						// sign.

						if (decimalPart != 0.0) {
							String parttokenchains_req;
							try {
								parttokenchains_req = input.readLine();

							} catch (SocketException e) {

								APIResponse.put("did", "");
								APIResponse.put("tid", "null");
								APIResponse.put("status", "Failed");
								APIResponse.put("message", "Part token chain hashing req not received.");

								output.close();
								input.close();
								sk.close();
								ss.close();
								return APIResponse.toString();

							}

							//TokenReceiverLogger.debug("!@#$% 2: " + parttokenchains_req);

							if (parttokenchains_req.equals("New part token chain to be hashed")) {
								String PartTokenChainToBeHashed_1_string;

								try {
									PartTokenChainToBeHashed_1_string = input.readLine();

								} catch (SocketException e) {

									APIResponse.put("did", "");
									APIResponse.put("tid", "null");
									APIResponse.put("status", "Failed");
									APIResponse.put("message",
											"Receiver not ale to hash part token chains for the sender.");

									output.close();
									input.close();
									sk.close();
									ss.close();
									return APIResponse.toString();

								}

								//TokenReceiverLogger.debug("!@#$% 3: " + PartTokenChainToBeHashed_1_string);

								JSONArray partTokenChainToBeHashed = new JSONArray(PartTokenChainToBeHashed_1_string);

								String hashForChain = calculateHash(partTokenChainToBeHashed.toString(), "SHA3-256");

								String hashSignedwithPvtShare = getSignFromShares(pvt, hashForChain);

								JSONObject obj = partTokenChainToBeHashed
										.getJSONObject(partTokenChainToBeHashed.length() - 1);
								partTokenChainToBeHashed.remove(partTokenChainToBeHashed.length() - 1);
								obj.put("hash", hashForChain);
								obj.put("pvtShareBits", hashSignedwithPvtShare);

								partTokenChainToBeHashed.put(obj);

								output.println(partTokenChainToBeHashed.toString());

								//TokenReceiverLogger.debug("!@#$% 4: " + partTokenChainToBeHashed);

							}

							if (parttokenchains_req.equals("Old part token chains to be hashed")) {

								String PartTokenChainToBeHashed_2_string;

								try {
									PartTokenChainToBeHashed_2_string = input.readLine();

								} catch (SocketException e) {

									APIResponse.put("did", "");
									APIResponse.put("tid", "null");
									APIResponse.put("status", "Failed");
									APIResponse.put("message",
											"Receiver not ale to hash part token chains for the sender.");

									output.close();
									input.close();
									sk.close();
									ss.close();
									return APIResponse.toString();

								}
								//TokenReceiverLogger.debug("!@#$% 5: " + PartTokenChainToBeHashed_2_string);

								JSONObject PartTokenChainsToBeHashed = new JSONObject(
										PartTokenChainToBeHashed_2_string);

								JSONObject hashedAndSigned_partTokenChains = new JSONObject();

								for (int i = 0; i < partTokens.length(); i++) {

									JSONArray parttokenChain = PartTokenChainsToBeHashed
											.getJSONArray(partTokens.getString(i));

									String hashForChain2 = calculateHash(parttokenChain.toString(), "SHA3-256");

									String hashSignedwithPvtShare2 = getSignFromShares(pvt, hashForChain2);

									JSONObject obj2 = parttokenChain.getJSONObject(parttokenChain.length() - 1);
									parttokenChain.remove(parttokenChain.length() - 1);
									obj2.put("hash", hashForChain2);
									obj2.put("pvtShareBits", hashSignedwithPvtShare2);

									parttokenChain.put(obj2);

									hashedAndSigned_partTokenChains.put(partTokens.getString(i), parttokenChain);
								}

								output.println(hashedAndSigned_partTokenChains.toString());

								//TokenReceiverLogger.debug("!@#$% 6: " + hashedAndSigned_partTokenChains);

							}
						}

						TokenReceiverLogger.info("Transaction ID: " + tid + "Transaction Successful");
						APIResponse.put("did", senderDidIpfsHash);
						APIResponse.put("tid", tid);
						APIResponse.put("status", "Success");
						APIResponse.put("tokens", wholeTokens);
						APIResponse.put("comment", comment);
						APIResponse.put("message", "Transaction Successful");
						TokenReceiverLogger.info(" Transaction Successful");
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

		} catch (Exception e) {
			executeIPFSCommands(" ipfs p2p close -t /p2p/" + senderPeerID);
			TokenReceiverLogger.error("Exception Occurred", e);
			return APIResponse.toString();
		} finally {
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