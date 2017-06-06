package contract_net;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;

import org.apache.commons.math3.random.RandomGenerator;
import org.slf4j.LoggerFactory;
import com.github.rinde.rinsim.core.model.comm.CommDevice;
import com.github.rinde.rinsim.core.model.comm.CommDeviceBuilder;
import com.github.rinde.rinsim.core.model.comm.CommModel;
import com.github.rinde.rinsim.core.model.comm.CommUser;
import com.github.rinde.rinsim.core.model.pdp.Container;
import com.github.rinde.rinsim.core.model.pdp.DefaultPDPModel;
import com.github.rinde.rinsim.core.model.pdp.Depot;
import com.github.rinde.rinsim.core.model.pdp.PDPModel;
import com.github.rinde.rinsim.core.model.pdp.PDPModel.ParcelState;
import com.github.rinde.rinsim.core.model.pdp.PDPModel.VehicleState;
import com.github.rinde.rinsim.core.model.pdp.Parcel;
import com.github.rinde.rinsim.core.model.pdp.Vehicle;
import com.github.rinde.rinsim.core.model.road.RoadModel;
import com.github.rinde.rinsim.core.model.time.TickListener;
import com.github.rinde.rinsim.core.model.time.TimeLapse;
import com.github.rinde.rinsim.geom.Point;
import com.github.rinde.rinsim.core.model.comm.Message;
import com.github.rinde.rinsim.core.model.comm.MessageContents;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableList;


public class DispatchAgent implements CommUser, TickListener {



	private DefaultPDPModel defaultpdpmodel;
	// stillToBeAssignedParcelss uit simulator halen want Parcels zijn geregistreerd in de simulator
	private Collection<Parcel> toBeDispatchedParcels;
	private List<CNPMessage> CNPmessages = new ArrayList<CNPMessage>();
	private List<CNPMessage> unreadMessages = new ArrayList<CNPMessage>();
	private List<Proposal> proposals = new ArrayList<Proposal>();
	private List<Proposal> tooLateProposals = new ArrayList<Proposal>();
	private List<Proposal> rejectedProposals = new ArrayList<Proposal>();
	//list of potential VehicleAgent contractors
	private List<TruckAgent> potentialContractors = new ArrayList<TruckAgent>();
	// deze twee moeten in veiling
	private List<TruckAgent> lostContractors = new ArrayList<TruckAgent>();
	private TruckAgent winningContractor = null;
	private ArrayList<CommUser> commUsers = new ArrayList<CommUser>(); // TruckAgent commUsers coupled to DispatchAgent
	private Proposal bestProposal;
	//used to record the number of received messages
	//in this version, we impose the manager to wait till receiving answers from all the contractors
	private int numberOfreceivedMessages = 0;
	private AuctionResult auctionResult;
	private List<AuctionResult> auctionResults;
	private long currentTime;

	//record the agent responsible for the best proposal
	private Optional<CommDevice> commDevice;
	// settings of commDevice
	private long lastReceiveTime;
	private final double range;
	private final double reliability;
	static final double MIN_RANGE = .2;
	static final double MAX_RANGE = 1.5;
	static final long LONELINESS_THRESHOLD = 10 * 1000;
	private static final long AUCTION_DURATION = 1000;
	private final RandomGenerator rng;
	private CNPMessage cnpmessage;

	public DispatchAgent(DefaultPDPModel defaultpdpmodel, RandomGenerator rng, List<AuctionResult> auctionResults) {
		this.defaultpdpmodel = defaultpdpmodel;// defined in the main
		toBeDispatchedParcels = new ArrayList<Parcel>();
		this.auctionResults = auctionResults;
		commDevice = Optional.absent();
		// settings for commDevice belonging to DispatchAgent
		this.rng = rng;
		range = MIN_RANGE + rng.nextDouble() * (MAX_RANGE - MIN_RANGE);
		reliability = rng.nextDouble();
	}

	// thicklistener methods implemented
		@Override
		public void tick(TimeLapse timeLapse) {
			/*
		    if (!destination.isPresent()) {
		      destination = Optional.of(roadModel.get().getRandomPosition(rng));
		    }
		    roadModel.get().moveTo(this, destination.get(), timeLapse);
		    if (roadModel.get().getPosition(this).equals(destination.get())) {
		      destination = Optional.absent();
		    }
		    
		    */
			
			currentTime = timeLapse.getTime();
			dispatchParcels(currentTime, AUCTION_DURATION);
		
			if (commDevice.get().getUnreadCount() > 0) {
				unreadMessages = readMessages();

				for (CNPMessage m : unreadMessages) {

					switch (m.getType()) {

					case REFUSE:
						// do nothing
						break;
					case PROPOSE:
						CNPProposalMessage mess = (CNPProposalMessage)m;
						// check that only proposals that arrive before the auction deadline are added
						if(m.getTimeSent() - m.getAuction().getStartTime()  < m.getAuction().getAuctionDuration())
						{
							proposals.add(mess.getProposal());
						} else {
							m.getAuction().setActiveAuction(false);
							tooLateProposals.add(mess.getProposal());
						}
						break;
					case FAILURE:
						// do nothing or in more advanced form of the program: rebroadcast call for proposal
						break;
					case INFORM_DONE:
						// truck tells that parcel is delivered
						// TODO: store in AuctionResult that parcel is delivered
						break;
					case INFORM_RESULT:
						// receive message from truck telling that parcel is delivered and giving information about the actual travel time, travel distance, fuel level,...
						// TODO: store this information in AuctionResult
						// TODO: set status of Package on IS_DELIVERED if this was not yet the case
						
						break;
					default:
						break;
					}
				}
				generateAuctionResults(timeLapse);
			}
		}

	// which Parcels have to be dispatched to the different truckAgents?
	public Collection<Parcel> getANNOUNCEDParcels(){
		return defaultpdpmodel.getParcels(ParcelState.ANNOUNCED);
	}

	public Collection<Parcel> getAVAILABLEParcels(){
		return defaultpdpmodel.getParcels(ParcelState.AVAILABLE);
	}

	public Collection<Parcel> getToBeDispatchedParcels(){
		toBeDispatchedParcels = getANNOUNCEDParcels();
		toBeDispatchedParcels.addAll(getAVAILABLEParcels());
		return toBeDispatchedParcels;
	}

	// which truckAgents are available to perform a task?
	public Set<Vehicle> getTruckAgents(){
		return defaultpdpmodel.getVehicles();
	}

	public VehicleState getTruckAgentState(TruckAgent truckAgent){
		return defaultpdpmodel.getVehicleState(truckAgent);
	}

	// if the dispatch agent wants to communicate with all other commUsers
	// CNPMessage contains info about the Message and the ContractNetMessageType
	public void sendBroadcastMessage(CNPMessage content){
		if (!this.commDevice.isPresent()) {
			throw new IllegalStateException("No commdevice activated for this dispatch agent");
		}
		CommDevice device = this.commDevice.get();
		device.broadcast(content);
	}

	// if the dispatch agent wants to communicate to only one other CommUser, i.e. one specific truck
	public void sendDirectMessage(CNPMessage content, CommUser recipient) {
		if (!this.commDevice.isPresent()) {throw new IllegalStateException("No commdevice activated for dispatch agent");}
		CommDevice device = this.commDevice.get();
		device.send(content, recipient);
	}

	public void sendCallForProposals(Auction auction, Parcel parcel, long currentTime, long AUCTION_DURATION){
		ContractNetMessageType type = ContractNetMessageType.CALL_FOR_PROPOSAL;
		CNPMessage cnpMessage = new CNPMessage(auction, type, this, currentTime);
		sendBroadcastMessage(cnpMessage);
	}

	public void dispatchParcels(long currentTime, long AUCTION_DURATION){
		toBeDispatchedParcels = getAVAILABLEParcels();
		if(!toBeDispatchedParcels.isEmpty()){
			for(Parcel p: toBeDispatchedParcels){
				Auction auction = new Auction(this, p, currentTime, AUCTION_DURATION, true);
				sendCallForProposals(auction, p, currentTime, AUCTION_DURATION);
			}
		}
	}

	public Proposal selectBestProposal(List<Proposal> proposals){
		long maxProposal = proposals.get(0).getTimeCostProposal();
		Proposal bestProposal = proposals.get(0);
		for(Proposal p: proposals){
			if (p.getTimeCostProposal() > maxProposal){
				maxProposal = p.getTimeCostProposal();
				bestProposal = p;
			}
			
		}
		return bestProposal;
	}

    public List<CNPMessage> readMessages() {
        CommDevice device = this.commDevice.get();
        List<CNPMessage> contents = new ArrayList<CNPMessage>();
        if (device.getUnreadCount() != 0) {
            ImmutableList<Message> messages = device.getUnreadMessages();
            contents = getMessageContent(messages);
        }
        return contents;
    }

    
	public List<CNPMessage> getMessageContent(ImmutableList<Message> messages) {
		Iterator<Message> it = messages.iterator();
		List<CNPMessage> contents = new ArrayList<>();
		while (it.hasNext()) {
			Message message = it.next();
			CNPMessage content = (CNPMessage)message.getContents();
			contents.add(content);
		}
		return contents;
	}


	public void generateAuctionResults(TimeLapse timeLapse){
		bestProposal = selectBestProposal(proposals);
		if(bestProposal != null){
			sendAcceptProposal(bestProposal.getAuction(), ContractNetMessageType.ACCEPT_PROPOSAL, timeLapse);
			//TODO change ParcelState
		} else {
			// send REJECT_PROPOSAL message to all TruckAgents who sent a proposal to this auction, but did not win
			for(Proposal p: proposals){
				rejectedProposals.add(p);
				sendRejectProposal(p.getAuction(), ContractNetMessageType.REJECT_PROPOSAL, p.getProposer(), "lost auction", timeLapse);
			}
		}
		// send REJCECT_PROPOSAL message to all TruckAgent who sent their proposal after the auction deadline had passed
		for(Proposal p: tooLateProposals){
			rejectedProposals.add(p);
			sendRejectProposal(p.getAuction(), ContractNetMessageType.REJECT_PROPOSAL, p.getProposer(), "too late", timeLapse);
		}
		auctionResult = new AuctionResult(bestProposal.getAuction(), bestProposal, bestProposal.getProposer(), AUCTION_DURATION, rejectedProposals);
		auctionResults.add(auctionResult);
	}
	
	public void sendAcceptProposal(Auction auction, ContractNetMessageType type, TimeLapse time){
		CNPAcceptMessage cnpAcceptMessage = new CNPAcceptMessage(auction, type, this, bestProposal.getProposer(), bestProposal, time.getTime());
		sendDirectMessage(cnpAcceptMessage, bestProposal.getProposer());
	}
	
	public void sendRefusal(Auction auction, ContractNetMessageType type,TimeLapse time){
		CNPRefusalMessage cnpRefusalMessage = new CNPRefusalMessage(auction, type, this, auction.getSenderAuction(), type.toString(), time.getTime());
		sendDirectMessage(cnpRefusalMessage, auction.getSenderAuction());	
	}
	
	 
		public void sendRejectProposal(Auction auction, ContractNetMessageType s, CommUser loser, String rejectionReasen, TimeLapse time){
			CNPRejectMessage cnpRejectMessage = new CNPRejectMessage(auction, s, this, loser, rejectionReasen, time.getTime());
			//TODO loop through all elements from auctionLosers, and send each a direct message
			sendDirectMessage(cnpRejectMessage, loser);
		}


	public AuctionResult getAuctionResult() {
		return auctionResult;
	}

	public void setAuctionResult(AuctionResult auctionResult) {
		this.auctionResult = auctionResult;
	}

	// CommUser methods implemented
	@Override
	// not needed for us since the dispatch agent is a phone app, so it has not one position at a certain physical depot
	public Optional<Point> getPosition() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void setCommDevice(CommDeviceBuilder builder) {
		//    if (range >= 0) {
		if (range >= 0) {
			builder.setMaxRange(range);
		}
		commDevice = Optional.of(builder
				.setReliability(reliability)
				.build());
	}

	@Override
	public void afterTick(TimeLapse timeLapse) {
		// TODO Auto-generated method stub
	
	}
	commDevice = Optional.of(builder
			.setReliability(reliability)
			.build());
	
	}
	
	public void initRoadPDP(RoadModel pRoadModel, PDPModel pPdpModel) {
		// TODO waarmee moet deze methode overschreven worden?
		roadModel = Optional.of(pRoadModel);
		pdpModel = Optional.of(pPdpModel);
	}

	public long getCurrentTime() {
		return currentTime;
	}

	public void setCurrentTime(long currentTime) {
		this.currentTime = currentTime;
	}
	
	
}
