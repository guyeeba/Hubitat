/**
 *  NUT UPS Device Type for Hubitat
 *  Péter Gulyás (@guyeeba)
 *
 *  Usage:
 *  1. Add this code and "NUT Child UPS" as a device driver in the Hubitat Drivers Code section
 *  2. Set NUT server's IP and credentials
 *  3. ?
 *  4. Profit!
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

metadata {
	definition (name: "NUT UPS Driver", namespace: "guyee", author: "Peter GULYAS") {
	capability "Initialize"
    capability "Telnet"
	capability "Refresh"
	}
	
	attribute "State", "String"

	preferences {
        input name: "nutServerHost", type: "text", description: "IP or hostname of NUT server", title: "NUT server hostname"
        input name: "nutServerPort", type: "number", description: "Port number of NUT server", title: "NUT server port number", defaultValue: 3493, range: "1..65535"
        input name: "nutServerLoginUsername", type: "text", description: "Username at NUT server", title: "NUT server username"
        input name: "nutServerLoginPassword", type: "password", description: "Password at NUT server", title: "NUT server password"
        input name: "nutReconnectDelay", type: "number", description: "Network reconnect delay", title: "Number of seconds to wait before initiating reconnect in case the connection goes down for whatever reason", defaultValue: 10, range: "1..600"
        input name: "nutPollingInterval", type: "number", description: "Polling interval", title: "Polling interval", defaultValue: 10, range: "1..600"
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
    }
}

import groovy.transform.Field

def parse(String message) {
	displayDebugLog "Received: ${message}"

	String[] msg = message.split("\"?( |\$)(?=(([^\"]*\"){2})*[^\"]*\$)\"?")
	
	switch (msg[0]) {
		case "BEGIN":
			parseBEGIN(msg.drop(1))
			break
		case "END":
			parseEND(msg.drop(1))
			break
		case "UPS":
			parseUPS(msg.drop(1))
			break
		case "VAR":
			parseVAR(msg.drop(1))
			break
		case "OK":
			parseOK(msg.drop(1))
			break
		default:
			log.error("Parse: Couldn't process message: \"${message}\"")
	}
}

def parseBEGIN(String[] msg) {
	switch (msg[0]) {
		case "LIST":
			parseBEGIN_LIST(msg.drop(1))
			break
		default:
			log.error("ParseBEGIN: Couldn't process message: \"${msg}\"")
	}
}

def parseBEGIN_LIST(String[] msg) {
	switch (msg[0]) {
		case "UPS":
			setState(STATE_PROCESSINGUPSLIST)
			break
		case "VAR":
			displayDebugLog "Processing of values for \"${msg[1]}\" started"
			break
		default:
			log.error("ParseBEGIN_LIST: Couldn't process message: \"${msg}\"")
	}
}

def parseEND(String[] msg) {
	switch (msg[0]) {
		case "LIST":
			parseEND_LIST(msg.drop(1))
			break
		default:
			log.error("ParseEND: Couldn't process message: \"${msg}\"")
	}
}

def parseEND_LIST(String[] msg) {
	switch (msg[0]) {
		case "UPS":
			refresh()
			break
		case "VAR":
			displayDebugLog "Processing of values for \"${msg[1]}\" finished"
			break
		default:
			log.error("ParseEND_LIST: Couldn't process message: \"${msg}\"")
	}
}

def parseUPS(String[] msg) {
	def childDev = getChildDevices()?.find { it.deviceNetworkId == getUPSDNID(msg[0]) }
	
	if (childDev == null) {
		log.info("New UPS found with ID: \"${msg[0]}\" and description: \"${msg[1]}\"")
		addChildDevice("guyee", "NUT Child UPS", getUPSDNID(msg[0]), [name: "NUTUPS${msg[0]}", label: "UPS ${msg[1]}", completedSetup: true, isComponent: false ])
	}
}

def parseVAR(String[] msg) {
	getChildDevice(getUPSDNID(msg[0]))?.parseVAR(msg.drop(1))
}

def parseOK(String[] msg) {
	switch(device.currentState("State", true).value) {
		case STATE_AUTH_PHASE1:
			nutAuthPhase2()
			break
		case STATE_AUTH_PHASE2:
			nutListUPS()
			break
		default:
			log.error("ParseOK: Couldn't process message: \"${msg}\"")
	}
}

def telnetStatus(String status){
	log.error("telnetStatus: ${status}")
	setState(STATE_NETWORKERROR, status)
	
	unschedule(scheduledRefresh)

	runIn(nutReconnectDelay.toInteger(), connectToServer)
}

def refresh() {
	setState(STATE_REFRESH)
	
	getChildDevices()?.each {
		it.refresh()
	}
	
	setState(STATE_WAITINGFOREVENT)

	runIn(nutPollingInterval, refresh)
}

def childRefresh(String upsName) {
	sendMsg("LIST VAR ${upsName}")
}

def initialize(){
	telnetClose()

	connectToServer()
}

def connectToServer() {
	unschedule(scheduledConnectToServer)
	unschedule(scheduledRefresh)
	
	if (nutServerHost != null && nutServerPort != null) {
		log.info "Opening telnet connection"
		setState(STATE_CONNECTING)
		telnetConnect([termChars:[10]], nutServerHost, nutServerPort.toInteger(), null, null)
		pauseExecution(1000)
		if (isAuthRequired()) {
			nutAuthPhase1()
		}
		else {
			nutListUPS()
		}
	} else {
		log.error "NUT server proprties not set"
	}
}

def isAuthRequired() {
	if (nutServerLoginUsername != null && nutServerLoginPassword != null) {
		return true
	} else {
		if (nutServerLoginUsername != null || nutServerLoginPassword != null) {
			log.warn "To authenticate to NUT server, both username AND password must be given. Defaulting to unathenticated session"
		}
		return false
	}
}

def nutAuthPhase1() {
	setState(STATE_AUTH_PHASE1)
	sendMsg("USERNAME ${nutServerLoginUsername}")
}

def nutAuthPhase2() {
	setState(STATE_AUTH_PHASE2)
	sendMsg("PASSWORD ${nutServerLoginPassword}")
}

def nutListUPS() {
	setState(STATE_GETTINGUPSLIST)
	sendMsg("LIST UPS")
}

def sendMsg(String msg) {
	displayDebugLog "Sending ${msg}"
	sendHubCommand(new hubitat.device.HubAction("${msg}", hubitat.device.Protocol.TELNET))
}

def installed(){
	initialize()
}

def updated(){
	initialize()
}

def getUPSDNID(String id) {
	return "${device.deviceNetworkId}-${id}"
}

def setState(String newState) {
	sendEvent([ name: "State", value: newState ])
}

def setState(String newState, String additionalInfo) {
	sendEvent([ name: "State", value: newState + " (${additionalInfo})", isStateChange: true ])
}

private def displayDebugLog(message) {
	if (logEnable) log.debug "${device.displayName}: ${message}"
}

@Field static final String STATE_CONNECTING = "Connecting"
@Field static final String STATE_GETTINGUPSLIST = "Getting list of UPSes"
@Field static final String STATE_PROCESSINGUPSLIST = "Processing list of UPSes"
@Field static final String STATE_REFRESH = "Refreshing values"
@Field static final String STATE_WAITINGFOREVENT = "Waiting for events to occur"
@Field static final String STATE_NETWORKERROR = "Network Error"
@Field static final String STATE_AUTH_PHASE1 = "Authentication - Phase 1"
@Field static final String STATE_AUTH_PHASE2 = "Authentication - Phase 2"
