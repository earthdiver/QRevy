import {defineConfig,devices} from '@playwright/test';
import {browserWorkers} from './scripts/browser-workers.mjs';
const testPort=process.env.QREVY_TEST_PORT||'4175';
const baseURL=`http://127.0.0.1:${testPort}`;
const webkitLaunch=process.env.QREVY_WEBKIT_EXECUTABLE?{executablePath:process.env.QREVY_WEBKIT_EXECUTABLE}:{};
export default defineConfig({
 // Concurrent local runs already use distinct server ports; isolate their
 // downloads and traces too so one run cannot delete another run's artifacts.
 outputDir:`../build/browser-tests/${testPort}`,
 webServer:{command:'npm run dev',url:baseURL,env:{PORT:testPort,HOST:'127.0.0.1'},reuseExistingServer:false,timeout:30000},
 testDir:'tests/browser',timeout:120000,expect:{timeout:20000},workers:browserWorkers(),
 use:{actionTimeout:20000,baseURL,locale:'ja-JP',trace:{mode:'retain-on-failure',screenshots:false}},
 projects:[
  {name:'iphone-webkit',use:{...devices['iPhone 13'],browserName:'webkit',launchOptions:webkitLaunch}},
  {name:'android-chromium',use:{...devices['Pixel 7'],browserName:'chromium'}},
  {name:'desktop-chromium',use:{browserName:'chromium',viewport:{width:1440,height:1000}}},
  {name:'desktop-firefox',use:{browserName:'firefox',viewport:{width:1440,height:1000}}},
  {name:'desktop-webkit',use:{browserName:'webkit',viewport:{width:1440,height:1000},launchOptions:webkitLaunch}}
 ]
});
