package org.minima.system.network.p2p;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.minima.objects.base.MiniData;
import org.minima.system.network.minima.NIOClientInfo;
import org.minima.system.network.p2p.messages.P2PGreeting;
import org.minima.system.network.p2p.messages.P2PWalkLinks;
import org.minima.system.network.p2p.params.P2PParams;
import org.minima.utils.MinimaLogger;
import org.minima.utils.json.JSONObject;
import org.minima.utils.messages.Message;

public class SwapLinksFunctions {

    public static JSONObject wrapP2PMsg(JSONObject data) {
        JSONObject msg = new JSONObject();
        msg.put("swap_links_p2p", data);
        return msg;
    }

    /**
     * Adds connections to the NoneP2PLinks set whilst waiting for a valid greeting.
     * Generates and send a greeting message to the newly connected client
     * Sends a request for its IP if the node's minima address has not been set yet
     *
     * @param state    the current P2P State
     * @param uid      the uid of the client that just connected
     * @param incoming if the connection is an incoming one
     * @param info     NIOClientInfo for the client that just connected
     * @return a list of messages to be sent (greeting and possible a request IP message)
     */
    public static List<Message> onConnected(P2PState state, String uid, boolean incoming, NIOClientInfo info) {
        List<Message> msgs = new ArrayList<>();
        //Get the details

        if (incoming) {
            state.getNoneP2PLinks().put(uid, new InetSocketAddress(info.getHost(), 0));
        } else {
            state.getNoneP2PLinks().put(uid, new InetSocketAddress(info.getHost(), info.getPort()));
        }

        P2PGreeting greeting = new P2PGreeting(state);
        msgs.add(new Message(P2PManager.P2P_SEND_MSG).addString("uid", uid).addObject("json", greeting.toJson()));

        if (state.getMyMinimaAddress() == null) {
            JSONObject requestIp = new JSONObject();
            MiniData secret = MiniData.getRandomData(12);
            state.setIpReqSecret(secret);
            requestIp.put("req_ip", secret.toString());
            msgs.add(new Message(P2PManager.P2P_SEND_MSG).addString("uid", uid).addObject("json", requestIp));
        }
        return msgs;
    }

    /**
     * If the node has more noneP2PNodes that its desired max then
     * Start a Random Walk to find a node that can take excess clients
     *
     * @param state   P2P State
     * @param clients all NIOClients
     * @return list of load balance walk messages to send
     */
    public static List<Message> onConnectedLoadBalanceRequest(P2PState state, List<NIOClientInfo> clients) {
        List<Message> msgs = new ArrayList<>();
        if (state.getMyMinimaAddress() != null && state.getNotAcceptingConnP2PLinks().size() > state.getMaxNumNoneP2PConnections() && !state.getInLinks().isEmpty()) {
            InetSocketAddress nextHop = UtilFuncs.selectRandomAddress(new ArrayList<>(state.getInLinks().values()));
            NIOClientInfo minimaClient = UtilFuncs.getClientFromInetAddress(nextHop, state);
            P2PWalkLinks walkLinks = new P2PWalkLinks(true, false, minimaClient.getUID());
            walkLinks.setClientWalk(true);
            msgs.add(new Message(P2PManager.P2P_SEND_MSG).addString("uid", minimaClient.getUID()).addObject("json", walkLinks.toJson()));
            MinimaLogger.log("[+] Sending client load balance request: " + walkLinks.toJson().toString());
        }
        return msgs;
    }

    public static void onDisconnected(P2PState state, Message zMessage) {
        //Get the details
        String uid = zMessage.getString("uid");
        boolean incoming = zMessage.getBoolean("incoming");
        boolean reconnect = zMessage.getBoolean("reconnect");

        // Remove uid from current connections
        InetSocketAddress removedAddress = null;
        if (incoming) {

            removedAddress = state.getInLinks().remove(uid);
            if (removedAddress == null) {
                removedAddress = state.getNotAcceptingConnP2PLinks().remove(uid);
            }
            if (removedAddress == null) {
                removedAddress = state.getNoneP2PLinks().remove(uid);
            }
        } else {
            removedAddress = state.getOutLinks().remove(uid);
        }

    }

    public static void updateKnownPeersFromGreeting(P2PState state, P2PGreeting greeting) {
        List<InetSocketAddress> newPeers = Stream.of(greeting.getInLinks(), greeting.getOutLinks(), greeting.getKnownPeers())
                .flatMap(Collection::stream)
                .distinct()
                .filter(x -> x.getPort() != 0)
                .filter(x -> !x.equals(state.getMyMinimaAddress()))
                .collect(Collectors.toCollection(ArrayList::new));

        state.getKnownPeers().addAll(newPeers);
//        if(state.getKnownPeers().contains(state.getMyMinimaAddress()))
        // TODO: Limit Set Size
    }

    public static boolean processGreeting(P2PState state, P2PGreeting greeting, String uid, NIOClientInfo client, boolean noconnect) {

        if (client != null) {
            String host = client.getHost();
            int port = greeting.getMyMinimaPort();
            InetSocketAddress minimaAddress = new InetSocketAddress(host, port);
            state.getNoneP2PLinks().remove(uid);
            if (greeting.isAcceptingInLinks()) {
                state.getKnownPeers().add(minimaAddress);
                // Peers are assumed to not be P2P Links until we get a valid P2P Greeting

                if (client.isIncoming()) {
                    state.getInLinks().put(uid, minimaAddress);
                } else {
                    state.getOutLinks().put(uid, minimaAddress);
                    if (state.getOutLinks().size() > P2PParams.TGT_NUM_LINKS) {
                        P2PFunctions.disconnect(uid);
                        MinimaLogger.log("[-] Too many outgoing connections, disconnecting");
                    }
                }

                // Disable no connect once we get a p2p connection
                if (noconnect) {
                    noconnect = false;
                }

                if (state.isDoingDiscoveryConnection()) {
                    state.setDoingDiscoveryConnection(false);
                    P2PFunctions.disconnect(uid);
                }
            } else {
                state.getNotAcceptingConnP2PLinks().put(uid, minimaAddress);
            }
        }
        return noconnect;
    }

    public static JSONObject processRequestIPMsg(JSONObject swapLinksMsg, String host) {
        MiniData secret = new MiniData((String) swapLinksMsg.get("req_ip"));
        JSONObject responseMsg = new JSONObject();
        JSONObject IpResponse = new JSONObject();
        IpResponse.put("res_ip", host);
        IpResponse.put("secret", secret.toString());
        responseMsg.put("swap_links_p2p", IpResponse);
        return responseMsg;
    }

    public static void processResponseIPMsg(P2PState state, JSONObject swapLinksMsg) {
        MiniData secret = new MiniData((String) swapLinksMsg.get("secret"));
        if (state.getIpReqSecret().isEqual(secret)) {
            String hostIP = (String) swapLinksMsg.get("res_ip");
            state.setMyMinimaAddress(hostIP);
            state.getKnownPeers().remove(state.getMyMinimaAddress());
            MinimaLogger.log("[+] Setting My IP: " + hostIP);
        } else {
            MinimaLogger.log("[-] Failed to set my ip. Secrets do not match. MySecret: " + state.getIpReqSecret() + " Received secret: " + secret);
        }
    }

    public static List<Message> joinScaleOutLinks(P2PState state, int targetNumLinks, ArrayList<NIOClientInfo> clients) {
        List<Message> sendMsgs = new ArrayList<>();
        InetSocketAddress nextHop = UtilFuncs.selectRandomAddress(new ArrayList<>(state.getOutLinks().values()));
        NIOClientInfo minimaClient = UtilFuncs.getClientFromInetAddress(nextHop, state);
        if (minimaClient != null && state.getOutLinks().size() < targetNumLinks) {
            P2PWalkLinks walkLinksMsg = new P2PWalkLinks(true, true, minimaClient.getUID());
            sendMsgs.add(new Message(P2PManager.P2P_SEND_MSG).addString("uid", minimaClient.getUID()).addObject("json", walkLinksMsg.toJson()));
        }
        return sendMsgs;
    }

    public static List<Message> requestInLinks(P2PState state, int targetNumLinks, ArrayList<NIOClientInfo> clients) {
        List<Message> sendMsgs = new ArrayList<>();
        if (state.isAcceptingInLinks() && state.getInLinks().size() < targetNumLinks) {
            InetSocketAddress nextHop = UtilFuncs.selectRandomAddress(new ArrayList<>(state.getOutLinks().values()));
            NIOClientInfo minimaClient = UtilFuncs.getClientFromInetAddress(nextHop, state);
            if (minimaClient != null) {
                P2PWalkLinks walkLinksMsg = new P2PWalkLinks(false, false, minimaClient.getUID());
                sendMsgs.add(new Message(P2PManager.P2P_SEND_MSG).addString("uid", minimaClient.getUID()).addObject("json", walkLinksMsg.toJson()));
            }
        }
        return sendMsgs;
    }

}
