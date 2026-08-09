package dev.evvie.waylandcraft.network;

import dev.evvie.waylandcraft.WaylandCraftCommon;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record SharedWindowImagePayload(long windowHandle, int frameNumber, int x, int y, int width, int height, byte[] imageData, double pivotX, double pivotY, double pivotZ, double normalX, double normalY, double normalZ, double downX, double downY, double downZ, double viewScale, int geometryWidth, int geometryHeight) implements CustomPacketPayload {
	
	public static final Identifier ID = Identifier.fromNamespaceAndPath(WaylandCraftCommon.MOD_ID, "shared_window_image");
	
	public static final CustomPacketPayload.Type<SharedWindowImagePayload> TYPE = new CustomPacketPayload.Type<>(ID);
	
	public static final StreamCodec<RegistryFriendlyByteBuf, SharedWindowImagePayload> CODEC = StreamCodec.of(
		(buf, payload) -> {
			buf.writeLong(payload.windowHandle);
			buf.writeVarInt(payload.frameNumber);
			buf.writeVarInt(payload.x);
			buf.writeVarInt(payload.y);
			buf.writeVarInt(payload.width);
			buf.writeVarInt(payload.height);
			buf.writeVarInt(payload.imageData.length);
			buf.writeBytes(payload.imageData);
			buf.writeDouble(payload.pivotX);
			buf.writeDouble(payload.pivotY);
			buf.writeDouble(payload.pivotZ);
			buf.writeDouble(payload.normalX);
			buf.writeDouble(payload.normalY);
			buf.writeDouble(payload.normalZ);
			buf.writeDouble(payload.downX);
			buf.writeDouble(payload.downY);
			buf.writeDouble(payload.downZ);
			buf.writeDouble(payload.viewScale);
			buf.writeVarInt(payload.geometryWidth);
			buf.writeVarInt(payload.geometryHeight);
		},
		buf -> {
			long windowHandle = buf.readLong();
			int frameNumber = buf.readVarInt();
			int x = buf.readVarInt();
			int y = buf.readVarInt();
			int width = buf.readVarInt();
			int height = buf.readVarInt();
			int dataLength = buf.readVarInt();
			byte[] imageData = new byte[dataLength];
			buf.readBytes(imageData);
			double pivotX = buf.readDouble();
			double pivotY = buf.readDouble();
			double pivotZ = buf.readDouble();
			double normalX = buf.readDouble();
			double normalY = buf.readDouble();
			double normalZ = buf.readDouble();
			double downX = buf.readDouble();
			double downY = buf.readDouble();
			double downZ = buf.readDouble();
			double viewScale = buf.readDouble();
			int geometryWidth = buf.readVarInt();
			int geometryHeight = buf.readVarInt();
			return new SharedWindowImagePayload(windowHandle, frameNumber, x, y, width, height, imageData, pivotX, pivotY, pivotZ, normalX, normalY, normalZ, downX, downY, downZ, viewScale, geometryWidth, geometryHeight);
		}
	);
	
	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
	
}
