package com.phairplay.dlna.renderer;

import org.jupnp.support.avtransport.impl.AVTransportStateMachine;
import org.jupnp.util.statemachine.States;

/**
 * AVTransport state machine for the DLNA media renderer.
 *
 * Three states (mirroring the Cling reference renderer):
 * NoMediaPresent -> Stopped -> Playing -> Stopped -> ...
 *
 * The seamless state-machine library generates the concrete implementation at
 * runtime, so this interface and all state classes must be kept by ProGuard.
 */
@States({
        DlnaNoMediaPresent.class,
        DlnaStopped.class,
        DlnaPlaying.class
})
public interface DlnaRendererStateMachine extends AVTransportStateMachine {
}
