# 已选图标：02 / Phosphor Broadcast

使用 GitHub Phosphor Icons 的 Broadcast Duotone 图形，原始 SVG 保存在本目录。已转换为 Android VectorDrawable，并使用系统 adaptive icon 遮罩。

## Pixel 主题图标

Android 13+ 的 `drawable-v33/ic_launcher.xml` 声明 `monochrome` 图层。用户在 Pixel「壁纸和样式」中开启主题图标后，启动器根据当前系统主题着色；应用不读取壁纸，也不自行强制开启这个系统设置。

单色图层保留波纹和圆环，省略中心 20% 透明填充。预览图 `themed-preview.png` 的紫色、暖色与深色仅为示例，不代表用户设备的实际配色。

未开启主题图标、旧版系统或不支持主题图标的启动器，显示 `icon_colors.xml` 中的中性灰默认配色。此前的薄荷绿预览是历史方案，不是最终默认配色。

通知小图标 `ic_hotspot_fg.xml` 没有改动，避免把启动图标的自适应留白或底色带进通知栏。

## 许可

来源：https://github.com/phosphor-icons/core/blob/main/assets/duotone/broadcast-duotone.svg

Copyright (c) 2023 Phosphor Icons，MIT License。完整许可随应用打包到 `assets/licenses/phosphor-icons.txt`。
