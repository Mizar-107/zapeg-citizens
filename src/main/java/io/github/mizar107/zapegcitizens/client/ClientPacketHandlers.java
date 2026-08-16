package io.github.mizar107.zapegcitizens.client;

import com.dwinovo.numen.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.NumenGateway;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import io.github.mizar107.zapegcitizens.network.packet.CitizenPromptS2C;
import io.github.mizar107.zapegcitizens.network.packet.CitizenReadyS2C;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Client-only bridge into Numen's owner-side agent loop. */
public final class ClientPacketHandlers {

    private ClientPacketHandlers() {}

    public static void onCitizenReady(CitizenReadyS2C message) {
        ProviderLibrary library = ProviderLibrary.instance();
        List<ProviderLibrary.Entry> providers = library.list();
        if (providers.size() == 1) {
            ProviderLibrary.Entry provider = providers.get(0);
            AgentLoopRegistry.get(message.citizenId()).ifPresentOrElse(
                    loop -> loop.setProviderEntry(provider.id()),
                    () -> library.assign(message.citizenId(), provider.id()));
            if (library.resolve(provider.id()).hasApiKey()) {
                tell("[Citizens] " + message.name() + " is ready with provider '"
                        + provider.name() + "'.");
            } else {
                tell("[Citizens] " + message.name() + " was created, but provider '"
                        + provider.name() + "' has no usable API key.");
            }
        } else {
            String assignedId = library.assignedEntry(message.citizenId());
            ProviderLibrary.Entry assigned = assignedId == null ? null : library.get(assignedId);
            if (assigned != null && library.resolve(assignedId).hasApiKey()) {
                tell("[Citizens] " + message.name() + " is ready with its existing provider '"
                        + assigned.name() + "'.");
            } else {
                tell("[Citizens] " + message.name()
                        + " is not provider-bound. This MVP requires exactly one configured provider; "
                        + "configure that, then ask an OP to rerun /citizen spawn "
                        + message.name() + " <player>.");
            }
        }
    }

    public static void onCitizenPrompt(CitizenPromptS2C message) {
        ProviderLibrary library = ProviderLibrary.instance();
        String providerId = library.assignedEntry(message.citizenId());
        ProviderLibrary.Entry provider = providerId == null ? null : library.get(providerId);
        if (provider == null) {
            tell("[Citizens] " + message.name()
                    + " is not ready: configure exactly one provider, then ask an OP to rerun "
                    + "/citizen spawn " + message.name() + " <player>.");
            return;
        }
        if (!library.resolve(providerId).hasApiKey()) {
            tell("[Citizens] " + message.name() + " is not ready: provider '"
                    + provider.name() + "' has no usable API key.");
            return;
        }

        boolean accepted = NumenGateway.enqueue(message.citizenId(), message.prompt());
        if (accepted) {
            tell("[Citizens] Task queued for " + message.name() + ": " + message.prompt());
        } else {
            tell("[Citizens] Could not queue the task for " + message.name()
                    + "; its Numen roster is not ready.");
        }
    }

    private static void tell(String text) {
        if (Minecraft.getInstance().player != null) {
            Minecraft.getInstance().player.displayClientMessage(Component.literal(text), false);
        }
    }
}
