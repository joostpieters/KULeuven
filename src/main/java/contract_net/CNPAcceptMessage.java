package contract_net;

import com.github.rinde.rinsim.core.model.comm.CommUser;
import com.google.common.base.Optional;

public class CNPAcceptMessage extends CNPMessage {
	private Proposal proposal;
	private CommUser receiver;

	public CNPAcceptMessage(Auction auction, ContractNetMessageType type, CommUser sender, CommUser receiver, Proposal proposal) {
		super(auction, type, sender);
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

}