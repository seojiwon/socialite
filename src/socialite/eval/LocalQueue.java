package socialite.eval;


import gnu.trove.TIntCollection;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.hash.TIntIntHashMap;

import java.util.ArrayList;
import java.util.List;

import socialite.tables.TableInst;
import socialite.util.ArrayQueue;
import socialite.util.Assert;


public class LocalQueue {
	static final int maxLevel=4;
	static { assert maxLevel>=2: "maxLevel should be larger than 2"; }
	
	TIntIntHashMap issuedRuleCounts;
	ArrayQueue<Task> recvQ;
	List<ArrayQueue<Task>> queues;
	int capacity;
	int size;
	List<Task> reservedTasks;
	int workerid;
	DeltaStepWindow deltaStepWindow;
	
	public LocalQueue(int _workerid) {
		capacity = 512;
		workerid = _workerid;
		queues = new ArrayList<ArrayQueue<Task>>();
		recvQ = new ArrayQueue<Task>(256);
		reservedTasks = new ArrayList<Task>(4);
		size = 0;
		issuedRuleCounts = new TIntIntHashMap(32);
		deltaStepWindow = new DeltaStepWindow(workerid);
		initQueues();
	}
	void initQueues() {
		for (int i=0; i<maxLevel; i++) {
			queues.add(new ArrayQueue<Task>(capacity));
		}
	}
	
	public int maxLevel() { return maxLevel; }	
	
	public DeltaStepWindow deltaStepWindow() {
		return deltaStepWindow;
	}
	
	public synchronized int size(int level) {
		assert level < maxLevel;
		if (level==-1) return recvQ.size();
		return queues.get(level).size();
	}
	
	public synchronized boolean containsAny(int[] rules) {
		for (int rule:rules) {
			if (issuedRuleCounts.containsKey(rule))
				return true;
		}
		return false;		
	}
	public synchronized boolean canRotate() {
		if (!deltaStepWindow.isEnabled()) return false;
		if (size==0) return false;		
		if (queues.get(0).size()!=0) return false;
		
		return true;
	}
	public synchronized boolean rotate() {
		if (!canRotate()) return false;
	
		ArrayQueue<Task> q = queues.remove(0);
		assert q.isEmpty();
		queues.add(q);		
		
		deltaStepWindow.step();		
		return true;
	}
	
	//@Override
	public synchronized void add(Task task) {
		add(0, task);
	}
	
//	@Override
	public synchronized void add(int priority, Task task) {
		if (priority >= maxLevel) priority = maxLevel-1;
		
		ArrayQueue<Task> q;
		if (priority==-1) q = recvQ;
		else q = queues.get(priority);
		task.setPriority(priority);
		q.add(task);
		addToIssuedRules(task.getRuleId());		
		size++;
	}
	
	void removeFromIssuedRules(int rule) {
		if (!issuedRuleCounts.containsKey(rule)) 
			return;
		
		int count = issuedRuleCounts.get(rule);
		if (count==1) {
			issuedRuleCounts.remove(rule);
		} else {
			assert count > 1:"Rule #"+rule+" is issued, not not in isseudRules";			
			issuedRuleCounts.put(rule, count-1);
		}
	}
	void addToIssuedRules(int rule) {		
		if (!issuedRuleCounts.containsKey(rule)) {
			issuedRuleCounts.put(rule, 1);
		} else {
			int count = issuedRuleCounts.get(rule);
			issuedRuleCounts.put(rule, count+1);
		}
	}

//	@Override
	public synchronized void addAll(int priority, Task[] tasks) {
		if (priority >= maxLevel) priority = maxLevel-1;
		ArrayQueue<Task> q;
		if (priority==-1) q = recvQ;
		else q = queues.get(priority);
		
		for (Task t:tasks) {
			if (t==null) continue;
			t.setPriority(priority);
			q.add(t);
			addToIssuedRules(t.getRuleId());
			size++;
		}
	}
	//@Override
	public synchronized void addAll(Task[] tasks) {
		addAll(1, tasks);
	}
	
	public synchronized void empty() {
		recvQ.clear();
		for (ArrayQueue<Task> q:queues) {
			if (q!=null)
				q.clear();
		}
		issuedRuleCounts.clear();
		size = reservedTasks.size();
	}
	
	public int likelySize() { return size; }
	public synchronized int size() { return size; }
	
	public synchronized boolean isEmpty() {
		return size==0;
	}

	public boolean isLikelyEmpty() {
		return size==0;
	}

	public boolean isLikelyEmptySoon() {
		return size <= 1;
	}

	public void printStat() {
		System.out.println("    recvQ.size:"+recvQ.size());
		
		for (int i=0; i<maxLevel; i++) {
			System.out.println("    queue["+i+"].size:"+queues.get(i).size());
		}
		
		System.out.println("    total size:"+size);
	}
	public synchronized void pop(Task task) {
		boolean removed=reservedTasks.remove(task);		
		assert removed;
		removeFromIssuedRules(task.getRuleId());
		size--;		
		assert size>=0;
	}		
	public synchronized Task reserveQuick(int level) {
		if (size==0) return null;		
		
		Task t=null;
		t = recvQ.get();
		if (t!=null) {
			reservedTasks.add(t);
			return t;
		}
		
		ArrayQueue<Task> q = queues.get(level);
		t = q.get();
		if (t==null) return null;

		EvalTask et = (EvalTask)t;
		if (et.getDeltaT()!=null) {
			invalidateFromCache(et.getDeltaT(), et.getPriority());			
		}
		reservedTasks.add(t);		
		return t;
	}
	void invalidateFromCache(TableInst deltaT, int priority) {
		assert deltaT!=null;		
		if (!deltaT.isAccessed()) 
			return;
		TmpTablePool.invalidate(workerid, deltaT, priority);
	}
	
	public int likelySizeAt(int level) {
		if (size==0) return 0;
		return recvQ.size() + queues.get(level).size();
	}
	public boolean likelyEmptyAt(int level) {
		if (size==0) return true;
		if (recvQ.size()==0 && queues.get(level).size()==0) 
			return true;
		return false;
	}
	
	public synchronized boolean steal(LocalQueue thief, int priorityLevel) {
		if (size==0) return false;
		
		Task t;
		t = recvQ.peek();
		if (t!=null && t.safeToSteal()) {
			t = recvQ.get();
			removeFromIssuedRules(t.getRuleId());
			size--;
			synchronized(thief) {
				thief.recvQ.add(t);
				thief.addToIssuedRules(t.getRuleId());
				thief.size++;
			}
			return true;
		}
		
		ArrayQueue<Task> q = queues.get(priorityLevel);
		if (q.size() == 0) return false;

		t = q.peek();
		if (!t.safeToSteal()) return false;
	
		removeFromIssuedRules(t.getRuleId());
		size--;
		t = q.get();
		assert t!=null;
		synchronized(thief) {
			thief.queues.get(priorityLevel).add(t);
			thief.addToIssuedRules(t.getRuleId());
			thief.size++;
		}
		
		return true;
	}
}