import java.util.concurrent.*;
import java.util.function.*;

// Pull Integer from in Q and map to new Integer in out Q
class TransformerNode implements Runnable {

    private final LinkedBlockingDeque<Integer> in; 
    private final LinkedBlockingDeque<Integer> out;
    private final Function<Integer, Integer> transform;

    public TransformerNode(LinkedBlockingDeque<Integer> in, LinkedBlockingDeque<Integer> out,
			   Function<Integer, Integer> transform) {
	this.in        = in;
	this.out       = out;
	this.transform = transform;
    }

    public void run() {
	try {
	    while(true) {
		out.putLast(transform.apply(in.take()));
	    }
	}
	catch(InterruptedException e) {} 
    }
}
// Pull Integers from in Qs and push min Integer to outQ
class CollatorNode implements Runnable {
    // These data structures are a bit silly, but taking the no heap directive to heart
    // I didn't want to instantiate any objects in the constructor.
    // So main instantiates and a reference is passed in... hence the concurrent maps
    // The idea behind the maps is to have an easy way to have a collection of input channels
    // which I can relate to a finite number of held Integer values
    // ... but this does strike me as contrary to our no unbounded collections aspiration
    // however the only solutions I could think of to address this would either
    // hardcode the number of in-channel/held value pairs (which is gross) or else
    // move this logic out of the node and place it in another object (which works but
    // is really just kicking the can down the road), or code my own bounded map (which
    // I don't think is whats expected).
    // Hopefully I haven't violated he spirit of the exercise... crossing my fingers this is OK
    // In my own defense -- The number of map elements do not change... no new map keys

    private final ConcurrentHashMap<Integer, LinkedBlockingDeque<Integer>> ins;
    private final LinkedBlockingDeque<Integer> out;
    private final ConcurrentHashMap<Integer, Integer> slots;

    public CollatorNode(ConcurrentHashMap<Integer, LinkedBlockingDeque<Integer>> ins,
			LinkedBlockingDeque<Integer> out,
			ConcurrentHashMap<Integer, Integer> slots) {
	this.ins   = ins;
	this.out   = out;
	this.slots = slots;
    }

    public void run() {
	int sent     = 0;
	Integer resO = null;
	try {
	    while(true) {
		int res = Integer.MAX_VALUE;
		// for each in channel get head of Q only if our currently held Integer
		// from that Q is <= something we've sent out
		for(Integer k : ins.keySet()) {
		    Integer x = slots.get(k);
		    if(x == null || x.intValue() <= sent) {
			x = ins.get(k).take();
			slots.put(k, x);
			// only "put" to map & key k is obtained from the passed in-channel
			// collection.  slots.size always == ins.size -- ins doesn't mutate
		    }
		    // a bit clumsy below, but I'm trying to avoid "new Integer" so
		    // hold onto both our primitivized (sic?) Integer as well as
		    // our Integer object ref
		    int y = x.intValue();
		    if(y < res) {
			res  = y;
			resO = x;
		    }
		}
		sent = res;		// sent will now equal our next Hamming #
		out.putLast(resO);      // send our next Hamming # out
	    }
	}
	catch(InterruptedException e) {}
    }
}
// Node which pulls from one in channel and repeats the input across all output channels
class SplitterNode implements Runnable {
    private final LinkedBlockingDeque<Integer> in;
    // copy on write array may be a bit silly because it never mutates
    // but it is shared between main thread and this so a a matter of formality
    private final CopyOnWriteArrayList<LinkedBlockingDeque<Integer>> outs;

    public SplitterNode(LinkedBlockingDeque<Integer> in,
			CopyOnWriteArrayList<LinkedBlockingDeque<Integer>> outs) {
	this.in   = in;
	this.outs = outs;
    }

    public void run() {
	try {
	    while(true) {
		Integer i = in.take();
		for (LinkedBlockingDeque<Integer> lbd : outs) {
		    lbd.putLast(i);
		}
	    }
	}
	catch(InterruptedException e) {}
    }
}
// Node which pulls from in channel and consumes the signal
class ConsumerNode implements Runnable {
    private final LinkedBlockingDeque<Integer> in;
    private final CountDownLatch latch;
    private final Consumer<Integer> c;
    
    public ConsumerNode(LinkedBlockingDeque<Integer> in, CountDownLatch latch, Consumer<Integer> c) {
	this.in           = in;
	this.latch        = latch;
	this.c            = c;
    }

    public void run() {
	long consumeLimit = latch.getCount();
	try {
	    while(true) {
		Integer i = in.take();
		if (consumeLimit-- > 0) { // make sure we don't leak more than N
		    c.accept(i);
		    latch.countDown();
		}
	    }
	}
	catch(InterruptedException e) {}
    }
}

class HW8 {
    // The setup for this dataflow seems a bit heavy handed, but any clever setups
    // may end up being as many lines of code; so for this assignment I'll specify
    // all the components by hand.  Cooler would be some sort of data flow builder.
    public static void main(String[] args) {
	// How many Hamming #'s do you want?  Pick an N
	final int N = 60;
	final Thread[] nodes = new Thread[6];
	// Ugly hasmap keys to bind channels to held values from that channel
	final Integer ZERO   = new Integer(0);
	final Integer ONE    = new Integer(1);
	final Integer TWO    = new Integer(2);
	final Integer THREE  = new Integer(3);
	final Integer FOUR   = new Integer(4);
	// Instantiate our channels
	LinkedBlockingDeque<Integer> trans2ToCollator   = new LinkedBlockingDeque<Integer>();
	LinkedBlockingDeque<Integer> trans3ToCollator   = new LinkedBlockingDeque<Integer>();
	LinkedBlockingDeque<Integer> trans5ToCollator   = new LinkedBlockingDeque<Integer>();
	LinkedBlockingDeque<Integer> collatorToSplitter = new LinkedBlockingDeque<Integer>();
	LinkedBlockingDeque<Integer> splitterToConsumer = new LinkedBlockingDeque<Integer>();
	LinkedBlockingDeque<Integer> splitterToTrans2   = new LinkedBlockingDeque<Integer>();
	LinkedBlockingDeque<Integer> splitterToTrans3   = new LinkedBlockingDeque<Integer>();
	LinkedBlockingDeque<Integer> splitterToTrans5   = new LinkedBlockingDeque<Integer>();
	// Pair of maps to form a relation between a channel and a value previously pulled
	// from the channel.  I started in on making a class for this, but the code for it
	// was way uglier than what was used here to set up and what was used in collator to
	// pull.  I'm sure once I submit someone will show a much more elegant solution.
	ConcurrentHashMap<Integer, LinkedBlockingDeque<Integer>> cIns =
	    new ConcurrentHashMap<Integer, LinkedBlockingDeque<Integer>>();
	ConcurrentHashMap<Integer, Integer> cSlots = new ConcurrentHashMap<Integer, Integer>();
    
	cIns.put(ONE, trans2ToCollator);
	cSlots.put(ONE, ZERO);
	cIns.put(TWO, trans3ToCollator);
	cSlots.put(TWO, ZERO);
	cIns.put(THREE, trans5ToCollator);
	cSlots.put(THREE, ZERO);
	// Array to hold output channels for splitter.  This never mutates, but is shared
	// hence the copy on write
	CopyOnWriteArrayList<LinkedBlockingDeque<Integer>> sOut =
	    new CopyOnWriteArrayList<LinkedBlockingDeque<Integer>>();

	sOut.add(splitterToConsumer);
	sOut.add(splitterToTrans2);
	sOut.add(splitterToTrans3);
	sOut.add(splitterToTrans5);
	// when latch goes to zero main will interrupt all nodes causing them to fall out
	// of their infinte loops
	CountDownLatch latch = new CountDownLatch(N);
	// cable up our machine
	nodes[0] = new Thread(new TransformerNode(splitterToTrans2, trans2ToCollator,
						  (Integer I) -> new Integer(I.intValue() * 2)));
	nodes[1] = new Thread(new TransformerNode(splitterToTrans3, trans3ToCollator,
						  (Integer I) -> new Integer(I.intValue() * 3)));
	nodes[2] = new Thread(new TransformerNode(splitterToTrans5, trans5ToCollator,
						  (Integer I) -> new Integer(I.intValue() * 5)));
	nodes[3] = new Thread(new CollatorNode(cIns, collatorToSplitter, cSlots));
	nodes[4] = new Thread(new SplitterNode(collatorToSplitter, sOut));
	nodes[5] = new Thread(new ConsumerNode(splitterToConsumer, latch,
					       (Integer I) -> System.out.println(I)));
	// start the machine by inserting ONE into the splitter and start the threads
	try {
	    collatorToSplitter.put(ONE);
	    for(int i = 0; i < nodes.length; i++) {
		nodes[i].start();
	    }
	    latch.await();
	}
	catch(InterruptedException e) {}
	// we've gotten here either because our latch went to zero or somebody interrupted
	// Interrupt the threads we made and then everyone falls out the bottom.
	finally {
	    for(int i = 0; i < nodes.length; i++) {
		nodes[i].interrupt();
	    }
	}
    }
}
			      

		    
		
	    

	
