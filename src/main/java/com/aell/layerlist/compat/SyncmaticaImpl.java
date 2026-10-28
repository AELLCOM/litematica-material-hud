package com.aell.layerlist.compat;

import ch.endte.syncmatica.Context;
import ch.endte.syncmatica.Feature;
import ch.endte.syncmatica.communication.ClientCommunicationManager;
import ch.endte.syncmatica.communication.ExchangeTarget;
import ch.endte.syncmatica.communication.exchange.ModifyExchangeClient;
import ch.endte.syncmatica.communication.exchange.ShareLitematicExchange;
import ch.endte.syncmatica.data.LocalLitematicState;
import ch.endte.syncmatica.data.ServerPlacement;
import ch.endte.syncmatica.litematica.LitematicManager;
import ch.endte.syncmatica.network.PacketType;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

/** Вызовы Syncmatica с её типами; трогать только через {@link Syncmatica}, и только когда мод стоит. */
@Environment(EnvType.CLIENT)
final class SyncmaticaImpl
{
    private static final String ERR = "layerlist.schematics.sync.error.";

    private SyncmaticaImpl() {}

    private static @Nullable Context context()
    {
        Context con = LitematicManager.getInstance().getActiveContext();
        return con != null && con.isStarted() && con.getCommunicationManager() instanceof ClientCommunicationManager ? con : null;
    }

    private static @Nullable ExchangeTarget server(Context con)
    {
        return ((ClientCommunicationManager) con.getCommunicationManager()).getServer();
    }

    static boolean active()
    {
        Context con = context();
        return con != null && server(con) != null;
    }

    static boolean shared(SchematicPlacement placement)
    {
        return LitematicManager.getInstance().getActiveContext() != null && LitematicManager.getInstance().isSyncmatic(placement);
    }

    static @Nullable String share(SchematicPlacement placement)
    {
        Context con = context();
        ExchangeTarget server = con == null ? null : server(con);

        if (server == null)
        {
            return ERR + "inactive";
        }

        if (LitematicManager.getInstance().isSyncmatic(placement))
        {
            return ERR + "already";
        }

        if (placement.getSchematicFile() == null)
        {
            return ERR + "no_file";
        }

        if (! server.getFeatureSet().hasFeature(Feature.CORE_EX) && placement.isRegionPlacementModified())
        {
            return ERR + "subregions";
        }

        con.getCommunicationManager().startExchange(new ShareLitematicExchange(placement, server, con));
        return null;
    }

    static void hide(SchematicPlacement placement)
    {
        if (context() != null)
        {
            LitematicManager.getInstance().unrenderSchematicPlacement(placement);
        }
    }

    static void removeFromServer(SchematicPlacement placement)
    {
        ServerPlacement p = LitematicManager.getInstance().getRenderingSyncmatic(placement);

        if (p != null)
        {
            remove(p);
        }
    }

    static List<Syncmatica.Remote> remote()
    {
        Context con = context();

        if (con == null)
        {
            return List.of();
        }

        List<Syncmatica.Remote> out = new ArrayList<>();

        for (ServerPlacement p : con.getSyncmaticManager().getAll())
        {
            out.add(describe(con, p));
        }

        out.sort(Comparator.comparing(Syncmatica.Remote::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    static Syncmatica.@Nullable Remote remoteOf(SchematicPlacement placement)
    {
        Context con = context();
        ServerPlacement p = con == null ? null : LitematicManager.getInstance().getRenderingSyncmatic(placement);
        return p == null ? null : describe(con, p);
    }

    private static Syncmatica.Remote describe(Context con, ServerPlacement p)
    {
        LocalLitematicState state = con.getFileStorage().getLocalState(p);
        String owner = p.getOwner() == null ? "" : p.getOwner().getName();
        String file = p.getFileName() == null ? "" : p.getFileName();
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return new Syncmatica.Remote(p, p.getName(), file.substring(slash + 1), owner, p.getPosition(), p.getDimension(),
                LitematicManager.getInstance().isRendered(p), state.isLocalFileReady(),
                con.getCommunicationManager().getDownloadState(p));
    }

    static void load(Object handle)
    {
        if (context() != null)
        {
            LitematicManager.getInstance().renderSyncmatic((ServerPlacement) handle);
        }
    }

    static void unload(Object handle)
    {
        if (context() != null)
        {
            LitematicManager.getInstance().unrenderSyncmatic((ServerPlacement) handle);
        }
    }

    static void download(Object handle)
    {
        Context con = context();
        ExchangeTarget server = con == null ? null : server(con);
        ServerPlacement p = (ServerPlacement) handle;

        if (server == null || con.getCommunicationManager().getDownloadState(p))
        {
            return;
        }

        try
        {
            con.getCommunicationManager().download(p, server);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }

    static void remove(Object handle)
    {
        Context con = context();
        ExchangeTarget server = con == null ? null : server(con);

        if (server == null)
        {
            return;
        }

        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeUUID(((ServerPlacement) handle).getId());
        server.sendPacket(PacketType.REMOVE_SYNCMATIC, buf, con);
    }

    static @Nullable String requestEdit(SchematicPlacement placement)
    {
        Context con = context();
        ExchangeTarget server = con == null ? null : server(con);

        if (server == null)
        {
            return ERR + "inactive";
        }

        ServerPlacement p = LitematicManager.getInstance().getRenderingSyncmatic(placement);

        if (p == null)
        {
            return ERR + "not_shared";
        }

        if (! server.getFeatureSet().hasFeature(Feature.CORE_EX) && placement.isRegionPlacementModified())
        {
            return ERR + "subregions";
        }

        if (con.getCommunicationManager().getModifier(p) == null)
        {
            con.getCommunicationManager().startExchange(new ModifyExchangeClient(p, server, con));
        }

        return null;
    }

    static boolean editable(SchematicPlacement placement)
    {
        Context con = context();
        ServerPlacement p = con == null ? null : LitematicManager.getInstance().getRenderingSyncmatic(placement);
        return p != null && ! placement.isLocked() && con.getCommunicationManager().getModifier(p) instanceof ModifyExchangeClient;
    }

    static void finishEdit(SchematicPlacement placement)
    {
        Context con = context();
        ServerPlacement p = con == null ? null : LitematicManager.getInstance().getRenderingSyncmatic(placement);

        if (p != null && con.getCommunicationManager().getModifier(p) instanceof ModifyExchangeClient modify)
        {
            modify.conclude();
        }
    }
}
