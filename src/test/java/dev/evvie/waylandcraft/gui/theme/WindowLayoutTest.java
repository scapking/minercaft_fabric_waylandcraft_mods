package dev.evvie.waylandcraft.gui.theme;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WindowLayoutTest {

	@Test
	void containKeepsAspectRatioAndCenters() {
		// 800x600 源内容适配进 400x300 视口（同比例）→ 铺满
		var r = WindowLayout.fit(800, 600, 400, 300, WindowLayout.FitMode.CONTAIN);
		assertEquals(400, r.width());
		assertEquals(300, r.height());
		assertEquals(0, r.x());
		assertEquals(0, r.y());
	}

	@Test
	void containLetterboxesWhenAspectDiffers() {
		// 800x600 (4:3) 适配进 400x400 方视口 → 宽度铺满 400，高度 300，上下留边 50
		var r = WindowLayout.fit(800, 600, 400, 400, WindowLayout.FitMode.CONTAIN);
		assertEquals(400, r.width());
		assertEquals(300, r.height());
		assertEquals(0, r.x());
		assertEquals(50, r.y());
	}

	@Test
	void containLetterboxesWide() {
		// 1920x1080 (16:9) 适配进 400x400 → 宽度受限：w=400, h=225，上下留边
		var r = WindowLayout.fit(1920, 1080, 400, 400, WindowLayout.FitMode.CONTAIN);
		assertEquals(400, r.width());
		assertEquals(225, r.height());
		assertEquals(0, r.x());
		assertEquals(88, r.y()); // (400-225)/2 四舍五入
	}

	@Test
	void coverFillsViewport() {
		// 800x600 适配进 400x400 COVER → 高度铺满 400，宽 533（裁剪左右）
		var r = WindowLayout.fit(800, 600, 400, 400, WindowLayout.FitMode.COVER);
		assertEquals(400, r.height());
		assertEquals(533, r.width());
		assertEquals(-66, r.x());
		assertEquals(0, r.y());
	}

	@Test
	void invalidInputsReturnEmpty() {
		var r = WindowLayout.fit(0, 100, 400, 400, WindowLayout.FitMode.CONTAIN);
		assertEquals(0, r.width());
		assertEquals(0, r.height());
	}

	@Test
	void contentRectAccountsForTitleBar() {
		var r = WindowLayout.contentRect(10, 20, 200, 120, 18);
		assertEquals(10, r.x());
		assertEquals(38, r.y());
		assertEquals(200, r.width());
		assertEquals(102, r.height());
	}

	@Test
	void componentSizeNeverUpscales() {
		// 源 100x50，可用空间 1000x1000，标题栏 18 → 不放大，组件 = 100x(50+18)
		int[] size = WindowLayout.componentSize(100, 50, 1000, 1000, 18);
		assertEquals(100, size[0]);
		assertEquals(68, size[1]);
	}

	@Test
	void componentSizeShrinksToFit() {
		// 源 800x600，可用 200x150（含标题栏 18）→ 内容最大 150-18=132，scale=min(200/800,132/600)=0.22 → 176x132+18
		int[] size = WindowLayout.componentSize(800, 600, 200, 150, 18);
		assertEquals(176, size[0]);
		assertEquals(150, size[1]);
	}
}
