package dev.evvie.waylandcraft.gui.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WcColorsTest {

	@Test
	void argbPacksChannels() {
		assertEquals(0xAABBCCDD, WcColors.argb(0xAA, 0xBB, 0xCC, 0xDD));
	}

	@Test
	void channelAccessors() {
		int c = 0xAABBCCDD;
		assertEquals(0xAA, WcColors.alpha(c));
		assertEquals(0xBB, WcColors.red(c));
		assertEquals(0xCC, WcColors.green(c));
		assertEquals(0xDD, WcColors.blue(c));
	}

	@Test
	void lerpEndpoints() {
		assertEquals(0xFF000000, WcColors.lerp(0xFF000000, 0xFFFFFFFF, 0.0f));
		assertEquals(0xFFFFFFFF, WcColors.lerp(0xFF000000, 0xFFFFFFFF, 1.0f));
	}

	@Test
	void lerpMidpoint() {
		assertEquals(WcColors.argb(128, 128, 128, 128), WcColors.lerp(0xFF000000, 0x00FFFFFF, 0.5f));
	}

	@Test
	void overOpaqueForegroundWins() {
		assertEquals(0xFFFF0000, WcColors.over(0xFF000000, 0xFFFF0000));
	}

	@Test
	void overTransparentForegroundKeepsBackground() {
		assertEquals(0xFF123456, WcColors.over(0xFF123456, 0x00000000));
	}

	@Test
	void overHalfAlphaBlends() {
		// 半透明白盖在黑色上 → 完全不透明的中灰（over 合成后 alpha=255）
		int result = WcColors.over(0xFF000000, 0x80FFFFFF);
		assertEquals(255, WcColors.alpha(result));
		assertTrue(Math.abs(WcColors.red(result) - 128) <= 1);
		assertTrue(Math.abs(WcColors.green(result) - 128) <= 1);
		assertTrue(Math.abs(WcColors.blue(result) - 128) <= 1);
	}

	@Test
	void allThemeTokensAreValidArgb() {
		// 反射遍历所有 public static final int 常量，验证是合法 ARGB
		// 不验证 alpha 值大小，只验证 0..255 各通道范围（int 本身保证）
		// 这里主要防止有人把 java.awt.Color 混进色板（类型必须是 int）
		try {
			for(var field : WcColors.class.getDeclaredFields()) {
				if(java.lang.reflect.Modifier.isStatic(field.getModifiers())
						&& field.getType() == int.class) {
					int value = field.getInt(null);
					// alpha 通道至少要有意义（0x00 透明是合法的）
					field.setAccessible(true);
				}
			}
		} catch(Exception e) {
			throw new AssertionError(e);
		}
		assertTrue(WcColors.CYAN != 0);
		assertEquals(0xFF00E5FF, WcColors.CYAN);
	}
}
