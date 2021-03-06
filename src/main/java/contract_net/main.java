package contract_net;


/*
 * Copyright (C) 2011-2016 Rinde van Lon, iMinds-DistriNet, KU Leuven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import static com.google.common.collect.Maps.newHashMap;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.measure.unit.SI;

import org.apache.commons.math3.random.RandomGenerator;
import org.apache.commons.math3.util.MathArrays.Position;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Monitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.rinde.rinsim.core.Simulator;
import com.github.rinde.rinsim.core.model.comm.CommDevice;
import com.github.rinde.rinsim.core.model.comm.CommModel;
import com.github.rinde.rinsim.core.model.comm.CommUser;
import com.github.rinde.rinsim.core.model.pdp.DefaultPDPModel;
import com.github.rinde.rinsim.core.model.pdp.Depot;
import com.github.rinde.rinsim.core.model.pdp.PDPModel;
import com.github.rinde.rinsim.core.model.pdp.Parcel;
import com.github.rinde.rinsim.core.model.pdp.ParcelDTO;
import com.github.rinde.rinsim.core.model.road.CollisionGraphRoadModel;
import com.github.rinde.rinsim.core.model.road.MoveProgress;
import com.github.rinde.rinsim.core.model.road.RoadModel;
import com.github.rinde.rinsim.core.model.road.RoadModelBuilders;
import com.github.rinde.rinsim.core.model.time.TickListener;
import com.github.rinde.rinsim.core.model.time.TimeLapse;
import com.github.rinde.rinsim.event.Listener;
import com.github.rinde.rinsim.geom.Graph;
import com.github.rinde.rinsim.geom.MultiAttributeData;
import com.github.rinde.rinsim.geom.io.DotGraphIO;
import com.github.rinde.rinsim.geom.io.Filters;
import com.github.rinde.rinsim.pdptw.common.StatsTracker;
import com.github.rinde.rinsim.ui.View;
import com.github.rinde.rinsim.ui.renderers.GraphRoadModelRenderer;
import com.github.rinde.rinsim.ui.renderers.RoadUserRenderer;
import com.google.common.collect.ImmutableBiMap;


/**
 * Example showing a fleet of taxis that have to pickup and transport customers
 * around the city of Leuven.
 * <p>
 * If this class is run on MacOS it might be necessary to use
 * -XstartOnFirstThread as a VM argument.
 * @author Rinde van Lon
 */
public final class main {

  public static final Mode mode = Mode.PARALLEL_AUCTIONS;

  private static final int NUM_DEPOTS = 2;
  private static final int NUM_TRUCKS = 10;
  private static final int NUM_PARCELS = 300;
  private static final int NUM_CHARINGSTATIONS = 2;

  // time in ms
  private static final long SERVICE_DURATION = 60000;
  private static final int TRUCK_CAPACITY = 10;
  private static final int DEPOT_CAPACITY = 100;

  private static final int SPEED_UP = 4;
  private static final int MAX_CAPACITY = 3;
  private static final double NEW_PARCEL_PROB = 0; //TODO op .007 zetten 

  private static final String MAP_FILE = "/data/maps/leuven-simple.dot";
  private static final Map<String, Graph<MultiAttributeData>> GRAPH_CACHE =
    newHashMap();
  
//  private static final long TEST_STOP_TIME = 50000 * 10000;
  private static final int TEST_SPEED_UP = 64;
	protected static final Logger LOGGER = LoggerFactory
			.getLogger(main.class);
  private main() {}
 
  /**
   * Starts the {@link main}.
   * @param args The first option may optionally indicate the end time of the
   *          simulation.
   */
  public static void main(@Nullable String[] args) {
    final long endTime = args != null && args.length >= 1 ? Long
      .parseLong(args[0]) : Long.MAX_VALUE;

    final String graphFile = args != null && args.length >= 2 ? args[1]
      : MAP_FILE;
    run(true, endTime, graphFile, null /* new Display() */, null, null);
  }

  /**
   * Run the example.
   * @param testing If <code>true</code> enables the test mode.
   */
  public static void run(boolean testing) {
    run(testing, Long.MAX_VALUE, MAP_FILE, null, null, null);
  }

  /**
   * Starts the example.
   * @param testing Indicates whether the method should run in testing mode.
   * @param endTime The time at which simulation should stop.
   * @param graphFile The graph that should be loaded.
   * @param display The display that should be used to show the ui on.
   * @param m The monitor that should be used to show the ui on.
   * @param list A listener that will receive callbacks from the ui.
   * @return The simulator instance.
   */
  public static Simulator run(boolean testing, final long endTime,
      String graphFile,
      @Nullable Display display, @Nullable Monitor m, @Nullable Listener list) {

    final View.Builder view = createGui(testing, display, m, list);
    final Simulator simulator = Simulator.builder()
    	      .addModel(RoadModelBuilders.staticGraph(loadGraph(graphFile)))
    	      .addModel(DefaultPDPModel.builder())
    	      .addModel(CommModel.builder())
    	      .addModel(view)
    	      .build();
    final RandomGenerator rng = simulator.getRandomGenerator();
    final PDPModel pdpModel = simulator.getModelProvider().getModel(PDPModel.class);
    final DefaultPDPModel defaultpdpmodel = simulator.getModelProvider().getModel(DefaultPDPModel.class);
    final RoadModel roadModel = simulator.getModelProvider().getModel(
      RoadModel.class);
    final CommModel commModel = simulator.getModelProvider().getModel(CommModel.class);
    final List<AuctionResult> auctionResultsList;
    final ArrayList<DispatchAgent> dispatchAgents = new ArrayList<DispatchAgent>();
    final ArrayList<TruckAgent> truckAgents = new ArrayList<TruckAgent>();
    
    // generate an empty list to store the results of each auction
    final AuctionResults auctionResults = new AuctionResults();
    auctionResultsList = auctionResults.getAuctionResults();
    
    // add depots, trucks and parcels to simulator
    //TODO take into account depot capacity
    for (int i = 0; i < NUM_DEPOTS; i++) {
    	DispatchAgent dispatchAgent = new DispatchAgent(defaultpdpmodel, roadModel, rng, roadModel.getRandomPosition(rng));
    	simulator.register(dispatchAgent);
    	dispatchAgents.add(dispatchAgent);
    }
    

    for (int i = 0; i < NUM_TRUCKS; i++) {
    	TruckAgent truckAgent = null;
    	switch(mode){
    		case BASIC:
    			truckAgent = new TruckAgentBasic(defaultpdpmodel, roadModel, roadModel.getRandomPosition(rng),TRUCK_CAPACITY, rng);
    			break;
    		case PARALLEL_AUCTIONS:
    			truckAgent = new TruckAgentParallel(defaultpdpmodel, roadModel, roadModel.getRandomPosition(rng),TRUCK_CAPACITY, rng);
    			break;
    		case DRIVING_AUCTIONS:
    			truckAgent = new TruckAgentDriving(defaultpdpmodel, roadModel, roadModel.getRandomPosition(rng),TRUCK_CAPACITY, rng);
    			break;
    	}
    	truckAgents.add(truckAgent);
    	simulator.register(truckAgent);
    }
    
    
    for (int i = 0; i < NUM_PARCELS; i++) {
    	Parcel parcel = Parcel.builder(roadModel.getRandomPosition(rng),
                roadModel.getRandomPosition(rng))
                .serviceDuration(SERVICE_DURATION) /// this might cause problems since we calculate the PDP distance (which is SERVICE_DURATION) and we do not use a constant
                .neededCapacity(1 + rng.nextInt(MAX_CAPACITY)) // we did not yet do anything with capacity
                .build();
    	Customer cust = new Customer(parcel.getDto());
		simulator.register(cust);
		
		// Assign parcel to random DispatchAgent.
		dispatchAgents.get(rng.nextInt(dispatchAgents.size())).assignParcel(cust);
    }
  

    for (int i = 0; i < NUM_CHARINGSTATIONS; i++) {
    	ChargingStation chargingStation = new ChargingStation(roadModel.getRandomPosition(rng), roadModel, rng);
    	simulator.register(chargingStation);
    }

  
    simulator.addTickListener(new TickListener() {
      @Override
      public void tick(TimeLapse time) {
    	boolean done = true;
    	Collection<Parcel> parcels = pdpModel.getParcels(com.github.rinde.rinsim.core.model.pdp.PDPModel.ParcelState.values());
  		for (Parcel parcel : parcels) {
  			if(!pdpModel.getParcelState(parcel).isDelivered()){
  				done = false;
  				break;
  			}
  		}
  		
        if (done) {
        	System.out.println("RESULTS SUMMARY");
        	System.out.println("---------------");
        	System.out.println("TOTAL TIME = "+time.getStartTime());
        	getParcelResults(pdpModel);
        	getDistanceResults(truckAgents);
        	getTimeResults(truckAgents);
        	getNumberOfRecharges(truckAgents);
        	getMessagesTruckAgents(truckAgents);
        	getMessagesDispatchAgents(dispatchAgents);
        	getNumberOfDirectMessagesTruckAgents(truckAgents);
        	getNumberOfDirectMessagesDispatchAgents(dispatchAgents);
        	getNumberOfBroadcastMessagesDispatchAgents(dispatchAgents);
        	calculateAverageTimeCFPToDelivery(dispatchAgents);
          //System.out.println(simulator.getModelProvider().getModel(StatsTracker.class)
          //	      .getStatistics());
          System.out.println("END OF TEST");

          for(DispatchAgent da: dispatchAgents){
          	  writeToTxt(da.getAuctionResults(), da);
          	  //System.out.println(da.getAuctionResults().toString());
          }

          simulator.stop();
        }/* else if (rng.nextDouble() < NEW_PARCEL_PROB) {
        	Parcel parcel =Parcel.builder(roadModel.getRandomPosition(rng),
                    roadModel.getRandomPosition(rng))
                    .serviceDuration(SERVICE_DURATION) /// this might cause problems since we calculate the PDP distance (which is SERVICE_DURATION) and we do not use a constant
                    .neededCapacity(1 + rng.nextInt(MAX_CAPACITY)) // we did not yet do anything with capacity
                    .build();
        	Customer cust = new Customer(parcel.getDto());
    		simulator.register(cust);
    		
    		// Assign parcel to random DispatchAgent.
    		Set<DispatchAgent> dispatchAgents = (roadModel.getObjectsOfType(DispatchAgent.class));
    		int num = rng.nextInt(dispatchAgents.size());
    		int i = 0;
    		for (DispatchAgent dispatchAgent : dispatchAgents){
    		    if(i == num){
    		    	dispatchAgent.assignParcel(cust);
    		    	break;
    		    }
    			i++;
    		}
        }*/
      }

      @Override
      public void afterTick(TimeLapse timeLapse) {}
    });
    simulator.start();
    return simulator;
  }

  
  static View.Builder createGui(
      boolean testing,
      @Nullable Display display,
      @Nullable Monitor m,
      @Nullable Listener list) {

    View.Builder view = View.builder()
      .with(GraphRoadModelRenderer.builder())
      .with(RoadUserRenderer.builder()
      .withImageAssociation(DispatchAgent.class, "/graphics/perspective/tall-building-64.png")
      .withImageAssociation(TruckAgentBasic.class, "/graphics/flat/small-truck-64.png")
      .withImageAssociation(TruckAgentParallel.class, "/graphics/flat/small-truck-64.png")
      .withImageAssociation(TruckAgentDriving.class, "/graphics/flat/small-truck-64.png")
      .withImageAssociation(Customer.class, "/graphics/perspective/deliverypackage.png")
      .withImageAssociation(ChargingStation.class, "/contract_net/tankstation.png"))
      //.with(TaxiRenderer.builder(Language.ENGLISH))
      .withTitleAppendix("PDP Demo");

    if (testing) {
      view = view.withAutoClose()
        .withAutoPlay()
//        .withSimulatorEndTime(TEST_STOP_TIME)
        .withSpeedUp(TEST_SPEED_UP);
    } else if (m != null && list != null && display != null) {
      view = view.withMonitor(m)
        .withSpeedUp(SPEED_UP)
        .withResolution(m.getClientArea().width, m.getClientArea().height)
        .withDisplay(display)
        .withCallback(list)
        .withAsync()
        .withAutoPlay()
        .withAutoClose();
    }
    return view;
  }

  // load the graph file
  static Graph<MultiAttributeData> loadGraph(String name) {
    try {
      if (GRAPH_CACHE.containsKey(name)) {
        return GRAPH_CACHE.get(name);
      }
      final Graph<MultiAttributeData> g = DotGraphIO
        .getMultiAttributeGraphIO(
          Filters.selfCycleFilter())
        .read(
          main.class.getResourceAsStream(name));

      GRAPH_CACHE.put(name, g);
      return g;
    } catch (final FileNotFoundException e) {
      throw new IllegalStateException(e);
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }
  }
  
  /*
   * generate statistics
   */
  
  public static void getMessagesTruckAgents(ArrayList<TruckAgent> truckAgents){
	  int nrOfCancelMessages = 0;
	  int nrOfProposalMessages = 0;
	  int nrOfRefusalMessages = 0;
	  int nrOfFailureMessages = 0;
	  int nrOfInformDoneMessages = 0;
	  int nrOfInformResultMessages = 0;
	  for(TruckAgent truckAgent: truckAgents){
		  nrOfCancelMessages+=truckAgent.getNrOfCancelMessages();
		  nrOfProposalMessages+=truckAgent.getNrOfProposalMessages();
		  nrOfRefusalMessages+=truckAgent.getNrOfRefusalMessages();
		  nrOfFailureMessages+=truckAgent.getNrOfFailureMessages();
		  nrOfInformDoneMessages+=truckAgent.getNrOfInformDoneMessages();
		  nrOfInformResultMessages+=truckAgent.getNrOfInformResultMessages();
	  }
	  System.out.println("CANCEL messages "+nrOfCancelMessages);
	  System.out.println("PROPOSAL messages "+nrOfProposalMessages);
	  System.out.println("REFUSAL messages "+nrOfRefusalMessages);
	  System.out.println("FAILURE messages "+nrOfFailureMessages);
	  System.out.println("INFORM_DONE messages "+nrOfInformDoneMessages);
	  System.out.println("INFORM_RESULT messages "+nrOfInformResultMessages);
  }
  
  public static void getMessagesDispatchAgents(ArrayList<DispatchAgent> dispatchAgents){
	  int nrOfCFPMessages = 0;
	  int nrOfAcceptProposalMessages = 0;
	  int nrOfRejectProposalMessages = 0;
	  for(DispatchAgent dispatchAgent: dispatchAgents){
		  nrOfCFPMessages+=dispatchAgent.getNrOfCFPMessages();
		  nrOfAcceptProposalMessages+=dispatchAgent.getNrOfAcceptMessages();
		  nrOfRejectProposalMessages+=dispatchAgent.getNrOfRejectProposalMessages();
	  }
	  System.out.println("CALL_FOR_PROPOSAL messages "+nrOfCFPMessages);
	  System.out.println("ACCEPT_PROPOSAL messages "+nrOfAcceptProposalMessages);
	  System.out.println("REJECT_PROPOSAL messages "+nrOfRejectProposalMessages);
  }
  
  public static int getNumberOfDirectMessagesTruckAgents(ArrayList<TruckAgent> truckAgents){
	  int totalNumberOfDirectMessages = 0;
	  for(TruckAgent truckAgent: truckAgents){
		  totalNumberOfDirectMessages+=truckAgent.getNumberOfDirectMessages();
	  }
	  System.out.println("TOTAL NUMBER OF DIRECT MESSAGES sent from "+ truckAgents.size()+ " truckagents to " +NUM_DEPOTS+ " dispatchagents is "+totalNumberOfDirectMessages);
	  return totalNumberOfDirectMessages;
  }
  
  public static int getNumberOfDirectMessagesDispatchAgents(ArrayList<DispatchAgent> dispatchAgents){
	  int totalNumberOfDirectMessagesDispatchAgents = 0;
	  for(DispatchAgent dispatchAgent: dispatchAgents){
		  totalNumberOfDirectMessagesDispatchAgents+=dispatchAgent.getNumberOfDirectMessages();
	  }
	  System.out.println("TOTAL NUMBER OF DIRECT MESSAGES sent from "+ dispatchAgents.size()+ " dispatchagnts to " +NUM_TRUCKS+ " truckagents is "+totalNumberOfDirectMessagesDispatchAgents);
	  return totalNumberOfDirectMessagesDispatchAgents;
  }
  
  public static int getNumberOfBroadcastMessagesDispatchAgents(ArrayList<DispatchAgent> dispatchAgents){
	  int totalNumberOfBroadcastMessagesDispatchAgents = 0;
	  for(DispatchAgent dispatchAgent: dispatchAgents){
		  totalNumberOfBroadcastMessagesDispatchAgents+=dispatchAgent.getNumberOfBroadCastMessages();
	  }
	  System.out.println("TOTAL NUMBER OF BROADCAST MESSAGES sent from "+ dispatchAgents.size()+ " dispatchagents to " +NUM_TRUCKS+ " truckagents is "+totalNumberOfBroadcastMessagesDispatchAgents);
	  return totalNumberOfBroadcastMessagesDispatchAgents;
  }
  
  public static int getNumberOfRecharges(ArrayList<TruckAgent> truckAgents){
	  int totalNumberOfRecharges = 0;
	  for(TruckAgent truckAgent: truckAgents){
		  totalNumberOfRecharges+=truckAgent.getNumberOfRecharges();
	  }
	  System.out.println("TOTAL NUMBER OF RECHARGES needed by "+ truckAgents.size()+ " truckagents is "+totalNumberOfRecharges);
	  return totalNumberOfRecharges;
  }
 
  public static long getDistanceResults(ArrayList<TruckAgent> truckAgents){
	  long totalDistanceTravelled = 0L;
	  for(TruckAgent truckAgent: truckAgents){
		  totalDistanceTravelled+=truckAgent.getTravelledDistance();
	  }
	  System.out.println("TOTAL DISTANCE travelled by "+ truckAgents.size()+ " truckagents is "+totalDistanceTravelled);
	  return totalDistanceTravelled;
  }
  
  public static long getTimeResults(ArrayList<TruckAgent> truckAgents){
	  long totalTimeTravelled = 0L;
	  for(TruckAgent truckAgent: truckAgents){
		  totalTimeTravelled+=truckAgent.getTravelledTime();
	  }
	  System.out.println("TOTAL TIME travelled by "+ truckAgents.size()+ " truckagents is "+(totalTimeTravelled/1000/60)+" min");
	  return totalTimeTravelled;
  }
  
  public static void getParcelResults(PDPModel pdpModel){

		int totalParcels = 0;
		int deliveredParcels = 0;
		int stillBeingTransportedParcels = 0;
		int notHandledParcels = 0;
		Collection<Parcel> parcels = pdpModel.getParcels(com.github.rinde.rinsim.core.model.pdp.PDPModel.ParcelState.values());
		totalParcels = parcels.size();
		for (Parcel parcel : parcels) {		
			if (pdpModel.getParcelState(parcel).isDelivered()) {
				deliveredParcels++;
			} else if (com.github.rinde.rinsim.core.model.pdp.PDPModel.ParcelState.IN_CARGO == pdpModel.getParcelState(parcel)){
				stillBeingTransportedParcels++;
				//System.out.println("Parcel "+parcel+" is still being transported.");
			} else {
				notHandledParcels++;
				//System.out.println("Parcel "+parcel+" is not delivered.");
			}
		}
		System.out.println("PARCELS delivered = "+deliveredParcels);
		System.out.println("PARCELS still being transported = "+stillBeingTransportedParcels);
		System.out.println("PARCELS not handled = "+notHandledParcels);
		System.out.println("PARCELS total = "+totalParcels);
	}
  
	public static long calculateAverageTimeCFPToDeliveryPerDispatchAgent(List<AuctionResult> auctionResults, DispatchAgent da) {
		long sumRealTimeCFPDelivery = 0L;	  
		for(AuctionResult auctionResult: auctionResults){
			sumRealTimeCFPDelivery+=auctionResult.getRealTimeCFPDelivery();	  
			  }
		return sumRealTimeCFPDelivery/auctionResults.size();
	}
	
	public static long calculateAverageTimeCFPToDelivery(List<DispatchAgent> dispatchAgents) {
	    long sumTimeCFPToDelivery = 0L;
	    for(DispatchAgent disp: dispatchAgents){
 	    	  sumTimeCFPToDelivery += calculateAverageTimeCFPToDeliveryPerDispatchAgent(disp.getAuctionResults(), disp);
	    	  //System.out.println(da.getAuctionResults().toString());
	    }
	    System.out.println("AVERAGE TIME BETWEEN CALL FOR PROPOSAL AND PARCEL DELIVERY: "+((sumTimeCFPToDelivery/dispatchAgents.size())/1000/60)+ " min");
	    return sumTimeCFPToDelivery/dispatchAgents.size();  
	}

/**
 * A customer with very permissive time windows.
 */
static class Customer extends Parcel {
  Customer(ParcelDTO dto) {
    super(dto);
  }

  @Override
  public void initRoadPDP(RoadModel pRoadModel, PDPModel pPdpModel) {}
}

//TODO: now only auctions with a proposal from a truckagent are added to auctionResulst, we also have to add the auctions without a proposal from a truckagent with value null for proposal and other inexisting values
public static void writeToTxt(List<AuctionResult> auctionResults, DispatchAgent da) {
	  PrintWriter writer = null;
	  try {
		  // generate a unique name for each experiment
		  String logFileName = new SimpleDateFormat("yyyyMMddHHmmss'.txt'").format(new Date());
		  String logNumberFileName = da+"_"+logFileName;
		  writer = new PrintWriter(new BufferedWriter(new FileWriter(logNumberFileName)));
		  for(AuctionResult auctionResult: auctionResults){
			  // auction result data are tab delimited, so we can read them as columns
			  writer.print(da.toString());
			  writer.print("\t");
			  writer.print(auctionResult.hashCode());
			  writer.print("\t");
			  writer.print(auctionResult.getAuction());
			  writer.print("\t");
			  writer.print(auctionResult.getWinner());
			  writer.print("\t");
			  writer.print(auctionResult.getBestProposal());
			  writer.print("\t");
			  writer.print(auctionResult.getBestProposal().getTimeCostProposal());
			  writer.print("\t");
			  writer.print(auctionResult.getBestProposal().getDistanceCostProposal(auctionResult.getBestProposal().getTimeCostProposal()));
			  writer.print("\t");
			  writer.print(auctionResult.getAuctionDuration());
			  writer.print("\t");
			  writer.print(auctionResult.getRealTimeTruckToPickup());
			  writer.print("\t");
			  writer.print(auctionResult.getRealTimePickupToDelivery());
			  writer.print("\t");
			  writer.print(auctionResult.getRealTimeTruckToPickupToDelivery());
			  writer.print("\t");
			  writer.print(auctionResult.getPickupTardiness(auctionResult.getBestProposal(),auctionResult.getRealTimeTruckToPickup()));
			  writer.print("\t");
			  writer.print(auctionResult.getDeliveryTardiness(auctionResult.getBestProposal(),auctionResult.getRealTimePickupToDelivery()));
			  writer.print("\t");
			  writer.print(auctionResult.getPickupDeliveryTardiness(auctionResult.getBestProposal(),auctionResult.getRealTimeTruckToPickupToDelivery()));
			  writer.print("\t");
			  writer.print(auctionResult.getRealTimeCFPDelivery());
			  writer.print("\t");
			  //TODO maybe leave away this one
			  writer.print(auctionResult.getRejectedProposals());
			  // new line for new auction
			  writer.println();
		  }
	  }
	  catch (IOException e){
		  e.printStackTrace();
	  }
	  finally {
		  if(writer !=null){
			  writer.close();
		  }
	  }
}
}
