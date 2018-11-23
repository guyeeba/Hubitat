metadata {
    definition (name: "Generic Child Switch", namespace: "guyee", author: "Péter Gulyás") {
        capability "Refresh"
        capability "Actuator"
        capability "Switch"
        capability "Light"
    }
}

def refresh() {
    parent.childRefresh(device.deviceNetworkId)
}

void on() { 
    log.debug "$device on"
    parent.childOn(device.deviceNetworkId)
}

void off() {
    log.debug "$device off"
    parent.childOff(device.deviceNetworkId)
}
