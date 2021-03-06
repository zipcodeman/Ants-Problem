import ants.*;
import java.lang.Math;
import java.util.ArrayDeque;
import java.util.Stack;
import java.util.Hashtable;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

/**
 * This is an ant that maps the world around it to get a good idea where to
 * travel next. 
 */
public class RadiusAnt implements Ant{
  /**
   * If the ant has food, then it can no longer collect food
   */
  private boolean hasFood = false;

  /**
   * The scout ant searches into the unknown.
   */
  private boolean isScout = false;

  /**
   * This is the current timestep, as far as this ant is concerned.
   */
  private int timeStep = 0;

  /**
   * This is the number of actions that the given ant has taken. Similar to
   * timestep, but does not get modified when we talk to other ants.
   */
  private int actionsTaken = 0;

  /**
   * The x distance from the anthill
   */
  private int x = 0;

  /**
   * The y distance from the anthill
   */
  private int y = 0;

  /**
   * This class will keep track of the world around the ants. 
   * When two ants pass each other, they will share information and update each
   * others' maps.
   */
  private WorldMap map = new WorldMap(5);

  /**
   * Each ant keeps track of a plan of movements for what it is currently trying
   * to achieve. The path is found using a breadth first search of the ant's
   * map.
   */
  private ArrayDeque<Action> plan;

  private Position anthill = new Position(0,0);

  private int radius = 40;
  /**
   * This is the subroutine to get the action for the ant to take. It returns an
   * action based on its current plan. This operation is very quick unless the
   * ant is forming a new plan. 
   * @param surroundings the squares that the ant can see.
   * @return The selected action.
   */
  @Override
  public Action getAction(Surroundings surroundings){
    // This will keep the surroundings updated with the new values gained from
    // surroundings. We want to make sure that we have the most up to date
    // information in the map. 
    updateSurroundings(surroundings);

    this.timeStep++;
    this.actionsTaken++;

    if(this.actionsTaken > 30){
      if(this.radius > 0 && this.actionsTaken % 10 == 0){
        this.radius -= 1;
      }
    }
    if(this.isScout){
      // After 20 turns, scouts turn back into gatherers
      if(this.actionsTaken == 20){
        plan = findFoodPlan();
        this.isScout = false;
      }

      // If our current plan is empty, make a new one.
      if(plan == null || plan.isEmpty())
        plan = intoTheUnknownPlan();

      if(plan == null){
        this.isScout = false;
        plan = deliverFoodPlan();
      }
      return this.makeMove(plan.pop());
    }else{
      Position here = new Position(x, y);

      // If we are on a food square, pick it up and start to travel back home.
      if(!hasFood && map.getFood(x, y) > 0 && anthill.manhattanDistance(here) > radius){
        plan = deliverFoodPlan();
        return this.makeMove(Action.GATHER);
      }

      // If we are at the anthill, drop off our food and look for more food.
      if(hasFood && anthill.manhattanDistance(here) <= radius){
        plan = findFoodPlan();
        return this.makeMove(Action.DROP_OFF);
      }

      // If we don't currently have a plan, construct one.
      if(plan == null || plan.isEmpty()){
        if(!hasFood){
          plan = findFoodPlan();
        }else{
          plan = deliverFoodPlan();
        }
      }

      // If we can't find what we want, just investigate unknown areas.
      if(plan == null || plan.isEmpty()){ 
        plan = intoTheUnknownPlan();
      }

      while(plan == null){
        if(!hasFood){
          plan = findFoodPlan();
        }else{
          plan = deliverFoodPlan();
        }
        radius--;
      }


      // Start performing the next action. 
      Action nextMove = plan.pop();
      
      // Ensure that we can indeed make this move. 
      if(map.validMove(nextMove, x, y, hasFood)){
        return this.makeMove(nextMove);
      }else{
        // This would mean that a plan sent us into a wall, which should never
        // happen.
        System.out.println("Plan attempted an invalid move. (" + x + ", " + y +
                           ") " + nextMove.getDirection());
        System.out.println(map);
        System.out.println();
        System.exit(0);
      }
      return null;
    }
  }

  /**
   * Sends data about the current ant, his timestep, and his map.
   * @return A byte array of the data we are sending.
   */
  @Override
  public byte[] send(){
    try{
      ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
      DataOutputStream dataWriter = new DataOutputStream(outputBytes);
      
      // Send the timestamp
      dataWriter.writeInt(this.timeStep);

      // Send my map
      map.serializeMap(dataWriter);

      return outputBytes.toByteArray();
    }catch(IOException e){
      System.err.println("Unable to send data!!!");
      return null;
    }
  }

  /**
   * Reads the data recieved from another ant, and updates his information
   * accordingly. 
   * @param data The byte array constructed by the other ant's send().
   */
  @Override
  public void receive(byte[] data){
    try{
      ByteArrayInputStream inputBytes = new ByteArrayInputStream(data);
      DataInputStream dataReader = new DataInputStream(inputBytes);

      int otherTimeStep = dataReader.readInt();
      
      // Make sure everyone's timesteps are aligned. 
      if(otherTimeStep > this.timeStep){
        map.adjustTimes(this.timeStep, otherTimeStep);
        this.timeStep = otherTimeStep;
      }

      // Load the other ant's map. 
      WorldMap otherMap = new WorldMap();
      otherMap.deserializeMap(dataReader);

      // Make it match your timestep.
      otherMap.adjustTimes(otherTimeStep, this.timeStep);
      
      // Add the information from the other ant into the current map. 
      map.mergeInto(otherMap);

      // Force a new plan (we assume that we will get new information from
      // mergeInto()
      this.plan = null;
    }catch(IOException e){
      System.err.println("Unable to recieve data!!!");
    }
  }

  /**
   * This adds the newly seen surrounding information to the map. In this way,
   * we make sure that the ant always has the most recent information about a
   * given square. 
   * @param s The surroundings passed to {@link #getAction(ants.Surroundings)} 
   */
  private void updateSurroundings(Surroundings s){
    map.updateMap(x, y,   s.getCurrentTile(), timeStep);
    map.updateMap(x, y-1, s.getTile(Direction.NORTH), timeStep);
    map.updateMap(x+1, y, s.getTile(Direction.EAST), timeStep);
    map.updateMap(x, y+1, s.getTile(Direction.SOUTH), timeStep);
    map.updateMap(x-1, y, s.getTile(Direction.WEST), timeStep);
  }
  
  ///////////////////////////////////////////////////////////
  //                                                       // 
  // This code is used to help the ant plan its next moves //
  //                                                       //
  ///////////////////////////////////////////////////////////
  
  /**
   * This is used to define the three different search goals. It makes it so we
   * can define a single search function
   */
  private interface SearchGoal{
    /**
     * This is the goal test.
     * @param p The current position of the ant.
     * @return true if the current position is at a goal state.
     */
    public boolean isGoal(Position p);

    /**
     * This simply returns an identifying string. Used to determine which
     * SearchGoal we are using
     */
    public String planName();
  }

  /**
   * This class keeps track of the moves that were taken by the ant to get to
   * this point, and the position of the ant. This is very useful for planning
   * purposes, and is what we keep in the Queue.
   */
  private class PartialPlan{
    /**
     * This is the current position of the ant. 
     */
    public Position p;

    /**
     * This is the list of actions taken to get to this point. 
     */
    public ArrayDeque<Action> moves;

    /**
     * Primary constructor, we start of with no movements and add the actions in
     * later. 
     * @param p The starting position of the ant
     */
    PartialPlan(Position p){
      this.p = p;
      moves = new ArrayDeque<Action>();
    }

    /**
     * Gets the current X position of the ant. 
     */
    public int getX(){
      return p.getX();
    }

    /**
     * Gets the current Y position of the ant.
     */
    public int getY(){
      return p.getY();
    }

    /**
     * This is how we add new movements to the partial plan. When we call this
     * method, we get a new PartialPlan that contains the newly added Move. The
     * Move contains both the new position and the action that was taken to get
     * there. In this way, we can keep adding to the moves Queue.
     * @param m The new move that is being added to the plan.
     * @return A new PartialPlan with the new Move added in. 
     */
    public PartialPlan planWithMove(Move m){
      PartialPlan retval = new PartialPlan(new Position(m.getPosition()));
      retval.moves = new ArrayDeque<Action>(this.moves);
      retval.moves.add(m.getAction());
      return retval;
    }

    /**
     * Returns the position object.
     */
		public Position getPosition(){
			return p;
		}

    /**
     * Converts to a string.
     */
    public String toString(){
      return "Partial Plan: " + p + " " +  moves;
    }
  }

  /**
   * This is one of the two major searching functions. This one searches for the
   * closest food spot.
   * @return The steps to get to the closest food
   */
  private ArrayDeque<Action> findFoodPlan(){
    return searchForGoal(new SearchGoal(){
      @Override
      public boolean isGoal(Position p){
        // We have reached the goal when we have a position with food that is
        // not the anthill.
        return (map.getFood(p.getX(), p.getY()) > 0 && 
                           anthill.manhattanDistance(p) > radius);
      }

      @Override
      public String planName(){
        return "Find Food";
      }
    });
  }

  /**
   * This is one of the two major searching functions. This one searches for the
   * anthill.
   * @return The steps to get to the anthill
   */
  private ArrayDeque<Action> deliverFoodPlan(){
    return searchForGoal(new SearchGoal(){
      @Override
      public boolean isGoal(Position p){
        return (anthill.manhattanDistance(p) <= radius && 
                (Math.abs(p.getX()) < 5 ||
                Math.abs(p.getY()) < 5)
                );
      }

      @Override
      public String planName(){
        return "Deliver Food";
      }
    });
  }

  /**
   * This plan is the fallback plan if the ant is unable to find food. It
   * searches for an area that has not been explored before. 
   * @return The steps to get to an unexplored square.
   */
  private ArrayDeque<Action> intoTheUnknownPlan(){
    return searchForGoal(new SearchGoal(){
      @Override
      public boolean isGoal(Position p){
        // If we are next to an unknown position, then we have reached our goal.
        return map.nextToUnknown(p.getX(), p.getY());
      }

      @Override
      public String planName(){
        return "the unknown";
      }
    });
  }

  /**
   * This is the generic function that drives the searching algorithm. We pass
   * in a SearchGoal, which is either looking for food, or for the anthill, and
   * we perform a breadth first search on the map until we find what we are
   * looking for or have exhausted all options. 
   * @param g The goal test class. 
   * @return the result of the search.
   */
  private ArrayDeque<Action> searchForGoal(SearchGoal g){
    // We keep track of potential paths in a queue.
    ArrayDeque<PartialPlan> fringe = new ArrayDeque<PartialPlan>();

    // We don't want to re-expand a given node, so we put it into the closed
    // set.
    Hashtable<Position, Boolean> closedSet = new Hashtable<Position, Boolean>();

    // We start off with a PartialPlan at the current position of the ant.
    PartialPlan start = new PartialPlan(new Position(this.x, this.y));
    boolean debug = false;
    if(debug)
      System.out.println(map);

    // We add our starting plan to the fringe.
    fringe.push(start);

    // If the fringe is ever empty, then we have exhausted all possibilites.
    while(!fringe.isEmpty()){
      // The current partial plan we are considering.
      PartialPlan consider = fringe.remove();

      // The map tells us the possible moves. (We wont plan to move into unknown
      // areas.
      Move[] successors = map.getPossibleMoves(consider.getX(), 
                                               consider.getY(),
                                               hasFood);
      Collections.shuffle(Arrays.asList(successors));
      // We make sure that the current position hasn't been considered yet, and
      // if it has, we continue to the next one.
      if(closedSet.put(consider.getPosition(), true) == null){
        // If we are at the goal, then we are done.
        if(g.isGoal(consider.getPosition())){
          return consider.moves;
        }
        // Add all of the successor states to the fringe.
        for(Move successor : successors){
          PartialPlan newPlan = consider.planWithMove(successor);
          fringe.add(newPlan);
        }
      }
    }

    // The only plan that can't return null is the unknown search.
    if(g.planName().equals("the unknown") && false){
      // The fringe is exhausted, so we choose a random direction from our list 
      // of possible directions.
      System.out.println("No path to " + g.planName() + 
                         " found. Using fallback");
      ArrayDeque<Action> backup = new ArrayDeque<Action>();
      Move[] p = map.getPossibleMoves(x, y, hasFood);
      int index = (int)(Math.random() * p.length);
      backup.add(p[index].getAction());
      return backup;
    }else{
      return null;
    }
  }

  /**
   * This helper function helps keep track of hasFood, x, and y. When we call
   * makeMove, we return the same action, but we update the variables
   * accordingly.
   * @param a The action we are taking
   * @return the action we passed in
   */
  private Action makeMove(Action a){
    if(a == Action.HALT){
      return a;
    }else if(a == Action.GATHER){
      this.hasFood = true;
      return a;
    }else if(a == Action.DROP_OFF){
      this.hasFood = false;
      return a;
    }else{
      switch(a.getDirection()){
        case NORTH:
          y -= 1;
          break;
        case EAST:
          x += 1;
          break;
        case SOUTH:
          y += 1;
          break;
        case WEST:
          x -= 1;
      }
      return a;
    }
  }
}
