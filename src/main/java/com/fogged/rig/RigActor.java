package com.fogged.rig;

/** A rendered entity that can ask the rig for an action: the end of an animation's name, empty for none. */
public interface RigActor {
    String rigAction();
}
