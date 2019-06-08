package com.direwolf20.buildinggadgets.common.util;

import com.direwolf20.buildinggadgets.common.blocks.ConstructionBlockTileEntity;
import com.direwolf20.buildinggadgets.common.config.Config;
import com.direwolf20.buildinggadgets.common.items.gadgets.GadgetBuilding;
import com.direwolf20.buildinggadgets.common.items.gadgets.GadgetExchanger;
import com.direwolf20.buildinggadgets.common.network.packets.PacketRotateMirror;
import com.direwolf20.buildinggadgets.common.util.tools.NetworkIO;
import com.direwolf20.buildinggadgets.common.util.tools.UndoState;
import com.direwolf20.buildinggadgets.common.util.tools.UniqueItem;
import com.direwolf20.buildinggadgets.common.util.tools.modes.BuildingMode;
import com.direwolf20.buildinggadgets.common.util.tools.modes.ExchangingMode;
import com.direwolf20.buildinggadgets.common.util.exceptions.CapabilityNotPresentException;
import com.direwolf20.buildinggadgets.common.util.helpers.InventoryHelper;
import com.direwolf20.buildinggadgets.common.util.helpers.NBTHelper;
import com.direwolf20.buildinggadgets.common.util.helpers.VectorHelper;
import com.direwolf20.buildinggadgets.common.util.ref.NBTKeys;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multiset;
import net.minecraft.block.BlockState;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.*;
import net.minecraft.util.Direction.Axis;
import net.minecraft.util.math.*;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class GadgetUtils {
    private static final ImmutableList<String> LINK_STARTS = ImmutableList.of("http","www");

    public static boolean mightBeLink(final String s) {
        return LINK_STARTS.stream().anyMatch(s::startsWith);
    }

    public static String getStackErrorSuffix(ItemStack stack) {
        return getStackErrorText(stack) + " with NBT tag: " + stack.getTag();
    }

    private static String getStackErrorText(ItemStack stack) {
        return "the following stack: [" + stack + "]";
    }

    public static CompoundNBT enforceHasTag(ItemStack stack) {
        CompoundNBT nbt = stack.getTag();
        if (nbt == null) {
            nbt = new CompoundNBT();
            stack.setTag(nbt);
        }
        return nbt;
    }

    @Nullable
    public static ByteArrayOutputStream getPasteStream(@Nonnull CompoundNBT compound, @Nullable String name) throws IOException {
        CompoundNBT withText = name != null && !name.isEmpty() ? compound.copy() : compound;
        if (name != null && !name.isEmpty()) withText.setString(NBTKeys.TEMPLATE_NAME, name);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CompressedStreamTools.writeCompressed(withText, baos);
        return baos.size() < Short.MAX_VALUE - 200 ? baos : null;
    }

    @Nonnull
    public static CompoundNBT getStackTag(ItemStack stack) {
        CompoundNBT tag = stack.getTag();
        if (tag == null)
            throw new IllegalArgumentException("An NBT tag could net be retrieved from " + getStackErrorText(stack));

        return tag;
    }

    public static void pushUndoList(ItemStack stack, UndoState undoState) {
        //When we have a new set of Undo Coordinates, push it onto a list stored in NBT, max 10
        CompoundNBT tagCompound = stack.getTag();
        if (tagCompound == null) {
            tagCompound = new CompoundNBT();
        }
        NBTTagList undoStates = (NBTTagList) tagCompound.getTag(NBTKeys.GADGET_UNDO_STACK);
        if (undoStates == null) {
            undoStates = new NBTTagList();
        }
        if (undoStates.size() >= 10) {
            undoStates.removeTag(0);
        }
        undoStates.add(undoStateToNBT(undoState));
        tagCompound.setTag(NBTKeys.GADGET_UNDO_STACK, undoStates);
        stack.setTag(tagCompound);
    }

    @Nullable
    public static UndoState popUndoList(ItemStack stack) {
        //Get the most recent Undo Coordinate set from the list in NBT
        CompoundNBT tagCompound = stack.getTag();
        if (tagCompound == null) {
            return null;
        }
        NBTTagList undoStates = (NBTTagList) tagCompound.getTag(NBTKeys.GADGET_UNDO_STACK);
        if (undoStates == null || undoStates.size() == 0) {
            return null;
        }
        UndoState undoState = NBTToUndoState(undoStates.getCompound(undoStates.size() - 1));
        undoStates.removeTag(undoStates.size() - 1);
        tagCompound.setTag(NBTKeys.GADGET_UNDO_STACK, undoStates);
        return undoState;
    }

    private static CompoundNBT undoStateToNBT(UndoState undoState) {
        //Convert an UndoState object into NBT data. Uses ints to store relative positions to a start block for data compression..
        CompoundNBT compound = new CompoundNBT();
        compound.setString(NBTKeys.GADGET_DIM, DimensionType.func_212678_a(undoState.dimension).toString());

        BlockPos startBlock = undoState.coordinates.get(0);
        int[] array = new int[undoState.coordinates.size()];
        int idx = 0;
        for (BlockPos coord : undoState.coordinates) {
            //Converts relative blockPos coordinates to a single integer value. Max range 127 due to 8 bits.
            int px = (((coord.getX() - startBlock.getX()) & 0xff) << 16);
            int py = (((coord.getY() - startBlock.getY()) & 0xff) << 8);
            int pz = (((coord.getZ() - startBlock.getZ()) & 0xff));
            int p = (px + py + pz);
            array[idx++] = p;
        }
        compound.setTag(NBTKeys.GADGET_UNDO_START_POS, NBTUtil.writeBlockPos(startBlock));
        compound.setIntArray(NBTKeys.GADGET_UNDO_INT_COORDS, array);
        return compound;
    }

    @Nullable
    private static UndoState NBTToUndoState(CompoundNBT compound) {
        //Convert an integer list stored in NBT into UndoState
        DimensionType dim = DimensionType.byName(new ResourceLocation(compound.getString(NBTKeys.GADGET_DIM)));
        if (dim == null)
            return null;

        List<BlockPos> coordinates = new ArrayList<BlockPos>();
        int[] array = compound.getIntArray(NBTKeys.GADGET_UNDO_INT_COORDS);
        BlockPos startBlock = NBTUtil.readBlockPos(compound.getCompound(NBTKeys.GADGET_UNDO_START_POS));
        for (int i = 0; i <= array.length - 1; i++) {
            int p = array[i];
            int x = startBlock.getX() + (byte) ((p & 0xff0000) >> 16);
            int y = startBlock.getY() + (byte) ((p & 0x00ff00) >> 8);
            int z = startBlock.getZ() + (byte) (p & 0x0000ff);
            coordinates.add(new BlockPos(x, y, z));
        }

        return new UndoState(dim, coordinates);
    }

    public static void setAnchor(ItemStack stack, List<BlockPos> coordinates) {
        //Store 1 set of BlockPos in NBT to anchor the Ghost Blocks in the world when the anchor key is pressed
        CompoundNBT tagCompound = stack.getTag();
        NBTTagList coords = new NBTTagList();
        if (tagCompound == null) {
            tagCompound = new CompoundNBT();
        }
        for (BlockPos coord : coordinates) {
            coords.add(NBTUtil.writeBlockPos(coord));
        }
        tagCompound.setTag(NBTKeys.GADGET_ANCHOR_COORDS, coords);
        stack.setTag(tagCompound);
    }

    public static List<BlockPos> getAnchor(ItemStack stack) {
        //Return the list of coordinates in the NBT Tag for anchor Coordinates
        CompoundNBT tagCompound = stack.getTag();
        List<BlockPos> coordinates = new ArrayList<BlockPos>();
        if (tagCompound == null) {
            setAnchor(stack, coordinates);
            return coordinates;
        }
        NBTTagList coordList = (NBTTagList) tagCompound.getTag(NBTKeys.GADGET_ANCHOR_COORDS);
        if (coordList == null) {
            setAnchor(stack, coordinates);
            return coordinates;
        }
        if (coordList.size() == 0) {
            return coordinates;
        }
        for (int i = 0; i < coordList.size(); i++) {
            coordinates.add(NBTUtil.readBlockPos(coordList.getCompound(i)));
        }
        return coordinates;
    }

    public static void setToolRange(ItemStack stack, int range) {
        //Store the tool's range in NBT as an Integer
        CompoundNBT tagCompound = NBTHelper.getOrNewTag(stack);
        tagCompound.setInt("range", range);
    }

    public static int getToolRange(ItemStack stack) {
        CompoundNBT tagCompound = NBTHelper.getOrNewTag(stack);
        return MathHelper.clamp(tagCompound.getInt("range"), 1, 15);
    }

    public static BlockState rotateOrMirrorBlock(ClientPlayerEntity player, PacketRotateMirror.Operation operation, BlockState state) {
        if (operation == PacketRotateMirror.Operation.MIRROR)
            return state.mirror(player.getHorizontalFacing().getAxis() == Axis.X ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK);

        return state.rotate(Rotation.CLOCKWISE_90);
    }

    public static void rotateOrMirrorToolBlock(ItemStack stack, ClientPlayerEntity player, PacketRotateMirror.Operation operation) {
        setToolBlock(stack, rotateOrMirrorBlock(player, operation, getToolBlock(stack)));
        setToolActualBlock(stack, rotateOrMirrorBlock(player, operation, getToolActualBlock(stack)));
    }

    private static void setToolBlock(ItemStack stack, @Nullable BlockState state) {
        //Store the selected block in the tool's NBT
        CompoundNBT tagCompound = stack.getTag();
        if (tagCompound == null) {
            tagCompound = new CompoundNBT();
        }
        if (state == null) {
            state = Blocks.AIR.getDefaultState();
        }

        CompoundNBT stateTag = NBTUtil.writeBlockState(state);
        tagCompound.setTag(NBTKeys.TE_CONSTRUCTION_STATE, stateTag);
        stack.setTag(tagCompound);
    }

    private static void setToolActualBlock(ItemStack stack, @Nullable BlockState state) {
        // Store the selected block actual state in the tool's NBT
        CompoundNBT tagCompound = stack.getTag();
        if (tagCompound == null) {
            tagCompound = new CompoundNBT();
        }
        if (state == null) {
            state = Blocks.AIR.getDefaultState();
        }

        CompoundNBT stateTag = NBTUtil.writeBlockState(state);
        tagCompound.setTag(NBTKeys.TE_CONSTRUCTION_STATE_ACTUAL, stateTag);
        stack.setTag(tagCompound);
    }

    public static BlockState getToolBlock(ItemStack stack) {
        CompoundNBT tagCompound = stack.getTag();
        if (tagCompound == null) {
            setToolBlock(stack, Blocks.AIR.getDefaultState());
            return Blocks.AIR.getDefaultState();
        }
        return NBTUtil.readBlockState(tagCompound.getCompound(NBTKeys.TE_CONSTRUCTION_STATE));
    }

    public static BlockState getToolActualBlock(ItemStack stack) {
        CompoundNBT tagCompound = stack.getTag();
        if (tagCompound == null) {
            setToolBlock(stack, Blocks.AIR.getDefaultState());
            return Blocks.AIR.getDefaultState();
        }
        return NBTUtil.readBlockState(tagCompound.getCompound(NBTKeys.TE_CONSTRUCTION_STATE_ACTUAL));
    }

    public static void selectBlock(ItemStack stack, ClientPlayerEntity player) {
        // Used to find which block the player is looking at, and store it in NBT on the tool.
        World world = player.world;
        RayTraceResult lookingAt = VectorHelper.getLookingAt(player, RayTraceFluidMode.NEVER);
        if (lookingAt == null)
            return;

        BlockPos pos = lookingAt.getBlockPos();
        EnumActionResult result = setRemoteInventory(stack, player, world, pos, true);
        if (result == EnumActionResult.SUCCESS)
            return;

        BlockState state = world.getBlockState(pos);
        if (result == EnumActionResult.FAIL || !Config.BLACKLIST.isAllowedBlock(state.getBlock())) {
            player.sendStatusMessage(new TextComponentString(TextFormatting.RED + new TextComponentTranslation("message.gadget.invalidblock").getUnformattedComponentText()), true);
            return;
        }
        BlockState placeState = InventoryHelper.getSpecificStates(state, world, player, pos, stack);
        BlockState actualState = placeState.getExtendedState(world, pos);

        setToolBlock(stack, placeState);
        setToolActualBlock(stack, actualState);
    }

    public static EnumActionResult setRemoteInventory(ItemStack stack, ClientPlayerEntity player, World world, BlockPos pos, boolean setTool) {
        TileEntity te = world.getTileEntity(pos);
        if (te == null)
            return EnumActionResult.PASS;

        if (setTool && te instanceof ConstructionBlockTileEntity && ((ConstructionBlockTileEntity) te).getBlockState() != null) {
            setToolBlock(stack, ((ConstructionBlockTileEntity) te).getActualBlockState());
            setToolActualBlock(stack, ((ConstructionBlockTileEntity) te).getActualBlockState());
            return EnumActionResult.SUCCESS;
        }
        if (setRemoteInventory(player, stack, pos, world))
            return EnumActionResult.SUCCESS;

        return EnumActionResult.FAIL;
    }

    public static boolean anchorBlocks(ClientPlayerEntity player, ItemStack stack) {
        //Stores the current visual blocks in NBT on the tool, so the player can look around without moving the visual render
        World world = player.world;
        List<BlockPos> currentCoords = getAnchor(stack);
        if (currentCoords.size() == 0) {  //If we don't have an anchor, find the block we're supposed to anchor to
            RayTraceResult lookingAt = VectorHelper.getLookingAt(player, stack);
            if (lookingAt == null) {  //If we aren't looking at anything, exit
                return false;
            }
            BlockPos startBlock = lookingAt.getBlockPos();
            Direction sideHit = lookingAt.sideHit;
            if (startBlock == null || world.getBlockState(startBlock) == Blocks.AIR.getDefaultState()) { //If we are looking at air, exit
                return false;
            }
            List<BlockPos> coords = new ArrayList<BlockPos>();
            if (stack.getItem() instanceof GadgetBuilding) {
                coords = BuildingMode
                        .collectPlacementPos(world, player, startBlock, sideHit, stack, startBlock); // Build the positions list based on tool mode and range
            } else if (stack.getItem() instanceof GadgetExchanger) {
                coords = ExchangingMode
                        .collectPlacementPos(world, player, startBlock, sideHit, stack, startBlock); // Build the positions list based on tool mode and range
            }
            setAnchor(stack, coords); //Set the anchor NBT
            player.sendStatusMessage(new TextComponentString(TextFormatting.AQUA + new TextComponentTranslation("message.gadget.anchorrender").getUnformattedComponentText()), true);
        } else {  //If theres already an anchor, remove it.
            setAnchor(stack, new ArrayList<BlockPos>());
            player.sendStatusMessage(new TextComponentString(TextFormatting.AQUA + new TextComponentTranslation("message.gadget.anchorremove").getUnformattedComponentText()), true);
        }
        return true;
    }

    public static boolean setRemoteInventory(ClientPlayerEntity player, ItemStack tool, BlockPos pos, World world) {
        if (getRemoteInventory(pos, DimensionType.func_212678_a(player.dimension), world) != null) {
            boolean same = pos.equals(getPOSFromNBT(tool, NBTKeys.REMOTE_INVENTORY_POS));
            writePOSToNBT(tool, same ? null : pos, NBTKeys.REMOTE_INVENTORY_POS, player.dimension);
            player.sendStatusMessage(new TextComponentString(TextFormatting.AQUA + new TextComponentTranslation("message.gadget." + (same ? "unboundTE" : "boundTE")).getUnformattedComponentText()), true);
            return true;
        }
        return false;
    }

    @Nullable
    public static IItemHandler getRemoteInventory(ItemStack tool, World world) {
         return getRemoteInventory(tool, world, NetworkIO.Operation.EXTRACT);

    }
    @Nullable
    public static IItemHandler getRemoteInventory(ItemStack tool, World world, NetworkIO.Operation operation) {
        ResourceLocation dim = getDIMFromNBT(tool, NBTKeys.REMOTE_INVENTORY_DIM);
        if (dim == null) return null;
        BlockPos pos = getPOSFromNBT(tool, NBTKeys.REMOTE_INVENTORY_POS);
        return pos == null ? null : getRemoteInventory(pos, dim, world /*, operation*/);
    }

    @Nullable
    public static IItemHandler getRemoteInventory(BlockPos pos, ResourceLocation dim, World world) {
        return getRemoteInventory(pos, dim, world, NetworkIO.Operation.EXTRACT);
    }

    @Nullable
    public static IItemHandler getRemoteInventory(BlockPos pos, ResourceLocation dimName, World world, NetworkIO.Operation operation) {
        DimensionType dim = DimensionType.byName(dimName);
        if (dim == null) return null;
        MinecraftServer server = world.getServer();
        if (server == null) return null;
        World worldServer = server.getWorld(dim);
        if (worldServer == null) return null;
        return getRemoteInventory(pos, worldServer, operation);
    }

    @Nullable
    public static IItemHandler getRemoteInventory(BlockPos pos, World world, NetworkIO.Operation operation) {

        TileEntity te = world.getTileEntity(pos);
        if (te == null) return null;
        //IItemHandler network = RefinedStorage.getWrappedNetwork(te, operation);
        //if (network != null) return network;

        LazyOptional<IItemHandler> cap = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, null);
        if( !cap.isPresent() )
            return null;

        return cap.orElseThrow(CapabilityNotPresentException::new);
    }

    public static String withSuffix(int count) {
        if (count < 1000) return "" + count;
        int exp = (int) (Math.log(count) / Math.log(1000));
        return String.format("%.1f%c",
                count / Math.pow(1000, exp),
                "kMGTPE".charAt(exp - 1));
    }

    public static void writePOSToNBT(ItemStack stack, @Nullable BlockPos pos, String tagName) {
        CompoundNBT tagCompound = stack.getTag();
        if (tagCompound == null) {
            tagCompound = new CompoundNBT();
        }
        if (pos == null) {
            if (tagCompound.getTag(tagName) != null) {
                tagCompound.removeTag(tagName);
                stack.setTag(tagCompound);
            }
            return;
        }
        tagCompound.setTag(tagName, NBTUtil.writeBlockPos(pos));
        stack.setTag(tagCompound);
    }

    public static void writePOSToNBT(ItemStack stack, @Nullable BlockPos pos, String tagName, DimensionType dimension) {
        CompoundNBT tagCompound = stack.getTag();
        if (tagCompound == null) {
            tagCompound = new CompoundNBT();
        }
        if (pos == null) {
            tagCompound.getTag(tagName);
            tagCompound.removeTag(tagName);
            stack.setTag(tagCompound);
            return;
        }
        CompoundNBT posTag = NBTUtil.writeBlockPos(pos);
        posTag.setString(NBTKeys.GADGET_DIM, DimensionType.func_212678_a(dimension).toString());
        tagCompound.setTag(tagName, posTag);
        stack.setTag(tagCompound);
    }

    public static void writePOSToNBT(CompoundNBT tagCompound, @Nullable BlockPos pos, String tagName, DimensionType dimension) {
        if (tagCompound == null) {
            tagCompound = new CompoundNBT();
        }
        if (pos == null) {
            tagCompound.getTag(tagName);
            tagCompound.removeTag(tagName);
            return;
        }
        tagCompound.setTag(tagName, NBTUtil.writeBlockPos(pos));
        tagCompound.setString(NBTKeys.GADGET_DIM, DimensionType.func_212678_a(dimension).toString());
    }

    @Nullable
    public static BlockPos getPOSFromNBT(ItemStack stack, String tagName) {
        CompoundNBT tagCompound = stack.getTag();
        if (tagCompound == null) {
            return null;
        }
        CompoundNBT posTag = tagCompound.getCompound(tagName);
        if (posTag.equals(new CompoundNBT())) {
            return null;
        }
        return NBTUtil.readBlockPos(posTag);
    }

    public static void writeIntToNBT(ItemStack stack, int tagInt, String tagName) {
        CompoundNBT tagCompound = stack.getTag();
        if (tagCompound == null) {
            tagCompound = new CompoundNBT();
        }
        tagCompound.setInt(tagName, tagInt);
        stack.setTag(tagCompound);
    }

    public static int getIntFromNBT(ItemStack stack, String tagName) {
        CompoundNBT tagCompound = stack.getTag();
        return tagCompound == null ? 0 : tagCompound.getInt(tagName);
    }

    public static void writeStringToNBT(ItemStack stack, String string, String tagName) {
        CompoundNBT tagCompound = stack.getTag();
        if (tagCompound == null) {
            tagCompound = new CompoundNBT();
        }
        if (string.equals(null)) {
            if (tagCompound.getTag(tagName) != null) {
                tagCompound.removeTag(tagName);
            }
            return;
        }
        tagCompound.setString(tagName, string);
    }

    public static void writeStringToNBT(CompoundNBT tagCompound, String string, String tagName) {//TODO unused
        if (tagCompound == null) {
            tagCompound = new CompoundNBT();
        }
        if (string.equals(null)) {
            if (tagCompound.getTag(tagName) != null) {
                tagCompound.removeTag(tagName);
            }
            return;
        }
        tagCompound.setString(tagName, string);
    }

    @Nullable
    public static String getStringFromNBT(ItemStack stack, String tagName) {
        CompoundNBT tagCompound = stack.getTag();
        if (tagCompound == null) {
            return null;
        }
        return tagCompound.getString(tagName);
    }

    @Nullable
    public static BlockPos getPOSFromNBT(CompoundNBT tagCompound, String tagName) {
        if (tagCompound == null) {
            return null;
        }
        CompoundNBT posTag = tagCompound.getCompound(tagName);
        if (posTag.equals(new CompoundNBT())) {
            return null;
        }
        return NBTUtil.readBlockPos(posTag);
    }

    @Nullable
    public static ResourceLocation getDIMFromNBT(ItemStack stack, String tagName) {
        CompoundNBT tagCompound = stack.getTag();
        if (tagCompound == null) {
            return null;
        }
        CompoundNBT posTag = tagCompound.getCompound(tagName);
        if (posTag.equals(new CompoundNBT())) {
            return null;
        }
        return new ResourceLocation(posTag.getString(NBTKeys.GADGET_DIM));
    }

    public static CompoundNBT stateToCompound(BlockState state) {
        return NBTUtil.writeBlockState(state);
    }

    @Nullable
    public static BlockState compoundToState(@Nullable CompoundNBT tagCompound) {
        if (tagCompound == null) {
            return null;
        }
        return NBTUtil.readBlockState(tagCompound);
    }

    public static int relPosToInt(BlockPos startPos, BlockPos relPos) {
        int px = (((relPos.getX() - startPos.getX()) & 0xff) << 16);
        int py = (((relPos.getY() - startPos.getY()) & 0xff) << 8);
        int pz = (((relPos.getZ() - startPos.getZ()) & 0xff));
        int p = (px + py + pz);
        return p;
    }

    public static BlockPos relIntToPos(BlockPos startPos, int relInt) {
        int p = relInt;
        int x = startPos.getX() + (byte) ((p & 0xff0000) >> 16);
        int y = startPos.getY() + (byte) ((p & 0x00ff00) >> 8);
        int z = startPos.getZ() + (byte) (p & 0x0000ff);
        return new BlockPos(x, y, z);
    }

    public static NBTTagList itemCountToNBT(Multiset<UniqueItem> itemCountMap) {
        NBTTagList tagList = new NBTTagList();

        for (Multiset.Entry<UniqueItem> entry : itemCountMap.entrySet()) {
            int item = Item.getIdFromItem(entry.getElement().getItem());
            int count = entry.getCount();
            CompoundNBT tagCompound = new CompoundNBT();
            tagCompound.setInt(NBTKeys.UNIQUE_ITEM_ITEM, item);
            tagCompound.setInt(NBTKeys.UNIQUE_ITEM_COUNT, count);
            tagList.add(tagCompound);
        }
        return tagList;
    }

    public static Multiset<UniqueItem> nbtToItemCount(@Nullable NBTTagList tagList) {
        if (tagList == null) return HashMultiset.create();
        Multiset<UniqueItem> itemCountMap = HashMultiset.create(tagList.size());
        for (int i = 0; i < tagList.size(); i++) {
            CompoundNBT tagCompound = tagList.getCompound(i);
            UniqueItem uniqueItem = new UniqueItem(Item.getItemById(tagCompound.getInt(NBTKeys.UNIQUE_ITEM_ITEM)));
            int count = tagCompound.getInt(NBTKeys.UNIQUE_ITEM_COUNT);
            itemCountMap.setCount(uniqueItem, count);
        }

        return itemCountMap;
    }

}