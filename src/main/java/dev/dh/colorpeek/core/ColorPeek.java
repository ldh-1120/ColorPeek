package dev.dh.colorpeek.core;

import com.mojang.logging.LogUtils;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(ColorPeek.MOD_ID)
public class ColorPeek {
    public static final String MOD_ID = "colorpeek";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final IEventBus MOD_BUS = FMLJavaModLoadingContext.get().getModEventBus();

    public ColorPeek() {

    }
}
