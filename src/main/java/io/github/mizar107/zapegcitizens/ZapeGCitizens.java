package io.github.mizar107.zapegcitizens;

import io.github.mizar107.zapegcitizens.network.CitizenNetwork;
import net.minecraftforge.fml.common.Mod;

@Mod(ZapeGCitizens.MOD_ID)
public final class ZapeGCitizens {

    public static final String MOD_ID = "zapeg_citizens";

    public ZapeGCitizens() {
        CitizenNetwork.register();
    }
}
