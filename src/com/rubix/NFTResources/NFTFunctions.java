package com.rubix.NFTResources;

import io.ipfs.api.*;
import org.apache.log4j.*;
import org.json.*;

import java.io.*;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.security.*;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMDecryptorProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.util.encoders.Base64;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemReader;

import static com.rubix.NFTResources.EnableNft.*;
import static com.rubix.Resources.APIHandler.*;
import static com.rubix.Resources.Functions.*;
import com.rubix.Resources.IPFSNetwork;

public class NFTFunctions {

    public static Logger NftFunctionsLogger = Logger.getLogger(NFTFunctions.class);

    /**
     * This method is used to create NFT tokens
     *
     * @param racType,DID,totalsupply,contenthash,url,comment,privatkeypass as
     *                                                                      String
     * @return JSONArray of NFT tokens
     */
    public static String createNftToken(String data) {
        pathSet();
        nftPathSet();
        JSONArray resultTokenArray = new JSONArray();
        JSONObject resultObject = new JSONObject();
        PrivateKey pvtKey;
        try {
            JSONObject apiData = new JSONObject(data);
            String keyPass = apiData.getString("pvtKeyPass");
            //String DID = apiData.getString("creatorDid");

            if (apiData.has("pvtKeyStr") && apiData.getString("pvtKeyStr") != null) {
                pvtKey = getPvtKeyFromStr(apiData.getString("pvtKeyStr"), keyPass);
                
                apiData.remove("pvtKeyStr");
            } else {
                pvtKey = getPvtKey(keyPass);
            }

            if(pvtKey == null || pvtKey.equals(""))
            {
                resultObject.put("Status", "Failed");
                resultObject.put("Tokens", resultTokenArray);
                resultObject.put("Message", "NFT tokens not created : Private Key Password mismatched");
                return resultObject.toString();
            }
            apiData.remove("pvtKeyPass");

            long totalSupply = apiData.getLong("totalSupply");
            for (long i = 1; i <= totalSupply; i++) {
                apiData.put("tokenCount", i);
                String pvtKeySign = pvtKeySign(apiData.toString(), pvtKey);
                apiData.put("pvtKeySign", pvtKeySign);

                writeToFile(LOGGER_PATH + "TempNftFile", apiData.toString(), false);
                String nftToken = IPFSNetwork.add(LOGGER_PATH + "TempNftFile", ipfs);

                writeToFile(NFT_TOKENS_PATH + nftToken, apiData.toString(), false);

                writeToFile(NFT_TOKENCHAIN_PATH + nftToken + ".json", "[]", false);

                deleteFile(LOGGER_PATH + "TempNftFile");

                resultTokenArray.put(nftToken);

                IPFSNetwork.pin(nftToken, ipfs);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            if (resultTokenArray.length() == 0) {
                resultObject.put("Status", "Failed");
                resultObject.put("Tokens", resultTokenArray);
                resultObject.put("Message", "NFT tokens not created");

            } else {
                resultObject.put("Status", "Success");
                resultObject.put("Tokens", resultTokenArray);
                resultObject.put("Message", "NFT tokens created");

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return resultObject.toString();

    }

    /**
     * This method is used to create RAC tokens
     *
     * @param racType,DID,totalsupply,contenthash,url,comment,privatkeypass as
     *                                                                      String
     * @return JSONArray of RAC tokens
     */
    public static String createRacToken(String data) {
        pathSet();
        nftPathSet();
        JSONArray resultTokenArray = new JSONArray();
        JSONObject resultObject = new JSONObject();
        try {
            JSONObject apiData = new JSONObject(data);
            String keyPass = apiData.getString("pvtKeyPass");
            String DID = apiData.getString("creatorDid");
            PrivateKey pvtKey;

            if (apiData.has("pvtKeyStr") && apiData.getString("pvtKeyStr") != null) {
                pvtKey = getPvtKeyFromStr(apiData.getString("pvtKeyStr"), keyPass);
                apiData.remove("pvtKeyStr");
            } else {
                pvtKey = getPvtKey(keyPass);
            }
            apiData.remove("pvtKeyPass");
            

            long totalSupply = apiData.getLong("totalSupply");
            for (long i = 1; i <= totalSupply; i++) {
                apiData.put("tokenCount", i);
                String pvtKeySign = pvtKeySign(apiData.toString(), pvtKey);
                apiData.put("pvtKeySign", pvtKeySign);

                writeToFile(LOGGER_PATH + "TempRACFile", apiData.toString(), false);
                String racToken = IPFSNetwork.add(LOGGER_PATH + "TempRACFile", ipfs);

                writeToFile(NFT_TOKENS_PATH + racToken, apiData.toString(), false);

                writeToFile(NFT_TOKENCHAIN_PATH + racToken + ".json", "[]", false);

                deleteFile(LOGGER_PATH + "TempRACFile");

                resultTokenArray.put(racToken);

                IPFSNetwork.pin(racToken, ipfs);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            if (resultTokenArray.length() == 0) {
                resultObject.put("Status", "Failed");
                resultObject.put("Tokens", resultTokenArray);
                resultObject.put("Message", "RAC tokens not created");

            } else {
                resultObject.put("Status", "Success");
                resultObject.put("Tokens", resultTokenArray);
                resultObject.put("Message", "RAC tokens created");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return resultObject.toString();
    }

    /**
     * This method is used to get decoded private key from .pem file
     *
     * @param passowrd private key password
     * @return private key
     */
    public static RSAPrivateKey getPvtKey(String password) {
        pathSet();

        String keyFile = DATA_PATH + "privatekey.pem";
        PrivateKey key = null;
        File privateKeyFile = new File(keyFile);
        PEMParser pemParser;
        Security.addProvider(new BouncyCastleProvider());

        try {
            pemParser = new PEMParser(new FileReader(privateKeyFile));

            Object object = pemParser.readObject();
            PEMDecryptorProvider decProv = new JcePEMDecryptorProviderBuilder().build(password.toCharArray());
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
            KeyPair kp = null;
            if (object instanceof PEMEncryptedKeyPair) {
                kp = converter.getKeyPair(((PEMEncryptedKeyPair) object).decryptKeyPair(decProv));
            }
            key = kp.getPrivate();
        } catch (Exception e) {
            System.out.println(e.toString());
        }
        return (RSAPrivateKey) key;

    }

    /**
     * This method is used to get decoded private key when the encoded private key
     * is supplied from the Central Wallet
     * 
     * @param pvtKeyStr private key String
     * @param passowrd  private key password
     * @return private key
     */

    public static PrivateKey getPvtKeyFromStr(String pvtKeyStr, String password) {
        PEMParser pemParser;
        PrivateKey key = null;
        Security.addProvider(new BouncyCastleProvider());

        pvtKeyStr=pvtKeyStr.replaceAll("\\\\n", "\n");

        try {
            pemParser = new PEMParser(new StringReader(pvtKeyStr));

            Object object = pemParser.readObject();
            PEMDecryptorProvider decProv = new JcePEMDecryptorProviderBuilder().build(password.toCharArray());
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
            KeyPair kp = null;
            if (object instanceof PEMEncryptedKeyPair) {
                kp = converter.getKeyPair(((PEMEncryptedKeyPair) object).decryptKeyPair(decProv));
            }
            key = kp.getPrivate();
            // System.out.println(key.toString());
        } catch (Exception e) {
            // TODO: handle exception
        }
        return  key;
    }

    /**
     * This method is used to Sign the string data with private key
     *
     * @param key  private key
     * @param data String to be signed
     * @return Signature as string
     */
    public static String pvtKeySign(String data, PrivateKey key) {
        // String result=null;
        byte[] signed = null;
        try {
            Signature signature = Signature.getInstance("SHA3-256withRSA");
            signature.initSign(key);
            byte[] raw = data.getBytes("UTF-8");
            signature.update(raw);
            signed = signature.sign();
        } catch (NoSuchAlgorithmException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (InvalidKeyException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (UnsupportedEncodingException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (SignatureException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        // return new String(Base64.encode(signed));
        return new String(Base64.encode(signed));
    }

    /**
     * This method is used to get decoded public key
     *
     * @param null
     * @return public key
     * 
     */
    public static PublicKey getPublicKey() {
        pathSet();
        String keyFile = DATA_PATH + "publickey.pub";
        // String password="foobar";
        PublicKey key = null;
        File publicKeyFile = new File(keyFile);
        Security.addProvider(new BouncyCastleProvider());

        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");

            FileReader keyReader = new FileReader(publicKeyFile);
            PemReader pemReader = new PemReader(keyReader);

            PemObject pemObject = pemReader.readPemObject();
            byte[] content = pemObject.getContent();
            X509EncodedKeySpec pubKeySpec = new X509EncodedKeySpec(content);
            key = factory.generatePublic(pubKeySpec);
        } catch (Exception e) {
            e.printStackTrace();
        }
        // System.out.println("\n"+key.toString());
        return (RSAPublicKey) key;
    }

    /**
     * This method is used to get decoded public key when encoded public key is
     * passed as string.
     *
     * @param Pubkeystr String Public key
     * @return public key
     * 
     */
    public static PublicKey getPubKeyFromStr(String Pubkeystr) {
        PublicKey key = null;
        Security.addProvider(new BouncyCastleProvider());

        Pubkeystr = Pubkeystr.replaceAll("\\\\n", "\n");

        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");

            PemReader pemReader = new PemReader(new StringReader(Pubkeystr));

            PemObject pemObject = pemReader.readPemObject();
            byte[] content = pemObject.getContent();
            X509EncodedKeySpec pubKeySpec = new X509EncodedKeySpec(content);
            key = factory.generatePublic(pubKeySpec);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return (RSAPublicKey) key;
    }

    /**
     * This method is used to verify the signature created with private key by using
     * the corresponding public key
     *
     * @param orgData    Original string data that was signed
     * @param pubKey     Public Key
     * @param pvtKeySign the Signture
     * @return boolean true if signtaure match else false
     */
    public static boolean verifySignature(String orgData, PublicKey pubKey, String pvtKeySign) {
        boolean result = false;
        byte[] pvtSign = pvtKeySign.getBytes();
        try {
            Signature s = Signature.getInstance("SHA3-256withRSA");
            s.initVerify(pubKey);
            s.update(orgData.getBytes());

            byte[] signatureBytes = Base64.decode(pvtSign);

            result = s.verify(signatureBytes);

        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return result;
    }

    public static boolean checkNftToken(String nftTokenIpfsHash) {
        pathSet();
        boolean result = false;
        String nftTokenString = readFile(NFT_TOKENS_PATH + nftTokenIpfsHash);
        try {
            JSONObject nftTokenObject = new JSONObject(nftTokenString);
            if (nftTokenObject.has("racType") && nftTokenObject.getInt("racType") == 1) {
                result = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    /*
     * This method check whether the calling nodes wallet is NFT compatitble i.e.
     * checks if the NFT folders are available
     * 
     */

    public static boolean checkWalletCompatibiltiy() {
        pathSet();
        nftPathSet();
        boolean result = false;
        File nftTokensFolder = new File(NFT_TOKENS_PATH);
        File nftTokenChainsFolder = new File(NFT_TOKENCHAIN_PATH);
        File nftSaleContractFolder = new File(NFT_SALE_CONTRACT_PATH);
        if (!nftTokensFolder.exists() || !nftTokenChainsFolder.exists() || !nftSaleContractFolder.exists()) {
            result = true;
        }
        return result;
    }

    /* Method to get the public key ipfs hash from file */
    public static String getPublicKeyIpfsHash() {
        pathSet();
        return readFile(DATA_PATH + "PublicKeyIpfsHash");
    }

    /*
     * Method to get the public key as string from ipfs when the ipfs hash of the
     * public key file is supplied
     */

    public static String getPubKeyStr() {
        String pubKeyIpfsHash=readFile(DATA_PATH+"PublicKeyIpfsHash");
        return IPFSNetwork.get(pubKeyIpfsHash, ipfs);
    }

    /*
     * Method to get the public key as string from .pub file
     */

    public static String getPubString() {
        pathSet();
        String keyFile = DATA_PATH + "publickey.pub";
        return readFile(keyFile);
    }

    /*
     * Method to check if the private and public key files are generated
     */
    public static boolean checkKeyFiles()
    {
        boolean result=false;

        pathSet();
        File privatekey = new File(DATA_PATH+"privatekey.pem");
        File publickey = new File(DATA_PATH+"publickey.pub");

        if(privatekey.exists() && publickey.exists())
        {
            result=true;
        }
        return result;
    }

    public static String getPubKeyIpfsHash()
    {
        pathSet();
        return (readFile(DATA_PATH+"PublicKeyIpfsHash"));
    }

    /* Method to create a contract fixing the value of NFT token to RBT */
    public static String createNftSaleContract(String data)
    {
        pathSet();
        nftPathSet();

        //NftFunctionsLogger.debug(data);
        String contractSign,saleContractIpfsHash=null;
        JSONObject contractDataObject= new JSONObject();
        JSONObject resultObj= new JSONObject();
        try {
            PrivateKey key;
            JSONObject dataObject= new JSONObject(data);
            if(dataObject.has("sellerPvtKey") && dataObject.getString("sellerPvtKey")!=null)
            {
                NftFunctionsLogger.debug("getting pvt key passed as string"+dataObject.getString("sellerPvtKey"));
                key=getPvtKeyFromStr(dataObject.getString("sellerPvtKey"), dataObject.getString("sellerPvtKeyPass"));
                dataObject.remove("sellerPvtKey");
            }
            else{
                NftFunctionsLogger.debug("getting pvt key stored in node");
                key=getPvtKey(dataObject.getString("sellerPvtKeyPass"));
            }
            if(key ==null || key.equals(""))
            {
                resultObj.put("status", "Failed");
                resultObj.put("message", "Pvt key Password Mismatch");
                resultObj.put("saleContractIpfsHash", "");

                return resultObj.toString();
            }

            dataObject.remove("sellerPvtKeyPass");

            contractDataObject.put("sellerDID", dataObject.getString("sellerDID"));
            contractDataObject.put("nftToken", dataObject.getString("nftToken"));
            contractDataObject.put("rbtAmount", dataObject.getDouble("rbtAmount"));
            //NftFunctionsLogger.debug(key);
            contractSign=pvtKeySign(dataObject.toString(), key);

            contractDataObject.put("sign", contractSign);
            

            writeToFile(LOGGER_PATH+"nftContract",contractDataObject.toString(), false);
            saleContractIpfsHash=IPFSNetwork.add(LOGGER_PATH+"nftContract", ipfs);
            NftFunctionsLogger.debug("Saving savle contract to "+NFT_SALE_CONTRACT_PATH + saleContractIpfsHash );
            writeToFile(NFT_SALE_CONTRACT_PATH+saleContractIpfsHash, contractDataObject.toString(), false);
            IPFSNetwork.pin(saleContractIpfsHash, ipfs);
            deleteFile(LOGGER_PATH+"nftContract");

            resultObj.put("status", "Success");
            resultObj.put("message", "Sale contract created");
            resultObj.put("saleContractIpfsHash", saleContractIpfsHash);

        } catch (JSONException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return resultObj.toString();
    }
}
