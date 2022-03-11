package com.rubix.Ping;
import io.ipfs.api.IPFS;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import static com.rubix.Resources.Functions.*;
import static com.rubix.Resources.IPFSNetwork.*;
import static com.rubix.Resources.IPFSNetwork.executeIPFSCommands;

public class PingReceive {
    public static Logger PingReceiverLogger = Logger.getLogger(PingReceive.class);

    private static final JSONObject APIResponse = new JSONObject();
    private static final IPFS ipfs = new IPFS("/ip4/127.0.0.1/tcp/" + IPFS_PORT);

    /**
     * Receiver Node: To receive a valid token from an authentic sender
     *
     * @return Transaction Details (JSONObject)
     * @throws IOException   handles IO Exceptions
     * @throws JSONException handles JSON Exceptions
     */
    public static String receive(int port) {
        pathSet();
        ServerSocket ss ;
        Socket sk ;

        try {
            repo(ipfs);


            PropertyConfigurator.configure(LOGGER_PATH + "log4jWallet.properties");

            String receiverPeerID = getPeerID(DATA_PATH + "DID.json");
            String appName = receiverPeerID.concat("Ping");
            listen(appName, port);
            ss = new ServerSocket(port);
            PingReceiverLogger.debug("Ping Receiver Listening on " + port + " appname " + appName);

            sk = ss.accept();
            PingReceiverLogger.debug("Data Incoming...");
            BufferedReader input = new BufferedReader(new InputStreamReader(sk.getInputStream()));
            PrintStream output = new PrintStream(sk.getOutputStream());

            String pingRequest;
            try {
                pingRequest = input.readLine();
            } catch (SocketException e) {
                PingReceiverLogger.warn("Sender Stream Null - PingCheck");
                APIResponse.put("did", "");
                APIResponse.put("tid", "null");
                APIResponse.put("status", "Failed");
                APIResponse.put("message", "Sender Stream Null - PingCheck");

                output.close();
                input.close();
                sk.close();
                ss.close();
                return APIResponse.toString();

            }
            PingReceiverLogger.debug("Ping Request Received: " + pingRequest);
            if(pingRequest != null && pingRequest.contains("PingCheck")) {
                output.println("Pong");

                APIResponse.put("status", "Success");
                APIResponse.put("message", "Pong Sent");
                PingReceiverLogger.info("Pong Sent");

            }else{
                APIResponse.put("status", "Failed");
                APIResponse.put("message", "Pong Failed");
                PingReceiverLogger.info("Pong Failed");
            }
            executeIPFSCommands(" ipfs p2p close -t /p2p/" + pingRequest);
            output.close();
            input.close();
            sk.close();
            ss.close();

        } catch (IOException e) {
            e.printStackTrace();
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return APIResponse.toString();
    }
}
