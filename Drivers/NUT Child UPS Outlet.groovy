metadata {
    definition (name: "NUT Child UPS Outlet", namespace: "guyee", author: "Péter Gulyás") {
        capability "Refresh"
		capability "Switch"
		
		// Outlet capabilities
		capability "VoltageMeasurement"
		capability "PowerMeter"		
	}
	
	attribute "switchable", "Boolean"
}

def refresh() {
    parent.refresh()
}

def parseOUTLET(String[] msg, String value) {
	switch (msg[0]) {
		case "id":
		break;
		case "status":
			Boolean isOn = value == "on"
			sendEvent( [
				name: 'switch',
				value: value ? "on" : "off",
				descriptionText: "Outlet status is ${value}"
			])
			break;			
		case "switchable":
			Boolean isSwitchable = (value == "yes")
			sendEvent( [
				name: 'switchable',
				value: isSwitchable,
				descriptionText: "Outlet is ${isSwitchable ? "" : "not"} switchable"
			])
			break;			
		default:
			log.error("ParseOUTLET: Couldn't process message: \"${msg}\"")
	}
}
