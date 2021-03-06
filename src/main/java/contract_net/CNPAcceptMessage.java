package contract_net;

import com.github.rinde.rinsim.core.model.comm.CommUser;
import com.google.common.base.Optional;

public class CNPAcceptMessage extends CNPMessage {
	private Proposal proposal;
	private CommUser receiver;

	public CNPAcceptMessage(Auction auction, ContractNetMessageType type, CommUser sender, CommUser receiver, Proposal proposal, long timeSent) {
		super(auction, type, sender, timeSent);
		this.proposal = proposal;
		this.receiver = receiver;
	}

	public Proposal getProposal() {
		return proposal;
	}

	public void setProposal(Proposal proposal) {
		this.proposal = proposal;
	}
	Optional<CommUser> to(){
		return Optional.of(receiver);
	}

	public CommUser getReceiver() {
		return receiver;
	}

	public void setReceiver(CommUser receiver) {
		this.receiver = receiver;
	}	
	
	public String toString(){
		String cnpMessage = super.toString();
		StringBuffer sb = new StringBuffer();
		sb.append(cnpMessage);
		sb.append("; ACCEPT message received by ");
		sb.append(receiver);
		sb.append(": Truckagent ");
		sb.append(super.getSender());
		sb.append(" has accepted the PDP task for parcel ");
		sb.append(super.getAuction().getParcel());
		sb.append(" with a cost in time of ");
		sb.append(proposal.getTimeCostProposal());
		return sb.toString();
	}
}
