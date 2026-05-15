package com.weartak.ipcservice;

oneway interface IWearTakAppCotListener {
    void receiveCotFromPlugin(String cotXml);
    void broadcastCot(String cotXml);
}
