import { Config } from '@remotion/cli/config';

// 本机没有可用的 Chrome Headless Shell（下载损坏/网络受限），
// 直接用系统里的 Edge（Chromium 内核）渲染。
Config.setBrowserExecutable('C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe');
Config.setVideoImageFormat('jpeg');
Config.setOverwriteOutput(true);
Config.setConcurrency(4);
