/*
 *  Orvibo Zigbee multi-functional relay (RL804QZB)
 *  Device Driver for Hubitat Elevation hub
 *
 *  Parent device button capability pulses the output of the device (emulating push button). The child switches are... just switches. :)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  https://www.orvibo.com/en/product/controlbox.html
 */

metadata {
	definition (name: "Orvibo Zigbee multi-functional relay (RL804QZB)", namespace: "guyee", author: "Péter GULYÁS") {
        capability "Configuration"
        capability "Refresh"
		capability "PushableButton"

        command "childPulse", [[ name: "endpoint", type:"ENUM", constraints: [1:"1",2:"2",3:"3"] ]]
        command "childSwitchOn", [[ name: "endpoint", type:"ENUM", constraints: [1:"1",2:"2",3:"3"] ]]
        command "childSwitchOff", [[ name: "endpoint", type:"ENUM", constraints: [1:"1",2:"2",3:"3"] ]]
        command "push", [ "Number" ]

		fingerprint endpointId: "03", profileId: "0104", inClusters: "0000,0005,0004,0006", outClusters: "0000", manufacturer: "ORVIBO", model: "82c167c95ed746cdbd21d6817f72c593"
	}

	preferences {
		//Pulse Length
 		input name: "pulseLength", title: "Pulse length in ms. Default = 500ms", description: "", type: "decimal", range: "100..10000", defaultValue: 500

        //Logging Message Config
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}
void logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

void updated(){
    log.info "updated..."
    log.warn "debug logging is: ${logEnable == true}"
    log.warn "description logging is: ${txtEnable == true}"
    if (logEnable) runIn(1800,logsOff)
}

def parse(String description) {
    def descMap = zigbee.parseDescriptionAsMap(description)
    
    if (txtEnable) log.info descMap

    def cluster = zigbee.clusterLookup(descMap?.clusterInt)
    
    if ([ "0A", "01" ].contains(descMap.command)) {
        switch (cluster.clusterEnum) {
            case "ON_OFF_CLUSTER": 
                def endpoint = zigbee.convertHexToInt(descMap.endpoint)
                def cd = fetchChild(endpoint);
                def newValue = descMap.value == "00" ? "off" : "on"

                cd.parse([[name: "switch", value: newValue]])
                break;
            default: 
                if (logEnable) log.info "parse called with unhandled cluster ${cluster.clusterLabel}"
        }
    }
}

def childPulse(endpoint){
    def cd = fetchChild(endpoint.toInteger())
    componentPulse(cd)
}

def childSwitchOn(endpoint){
    def cd = fetchChild(endpoint.toInteger())
    componentOn(cd)
}

def childSwitchOff(endpoint){
    def cd = fetchChild(endpoint.toInteger())
    componentOff(cd)
}

def fetchChild(int endpoint){
    String thisId = device.id
    def cd = getChildDevice("${thisId}-${endpoint}")
    if (!cd) {
        if (logEnable) log.info "Creating missing child device for endpoint ${endpoint}"
        cd = addChildDevice("hubitat", "Generic Component Switch", "${thisId}-${endpoint}", [name: "${device.displayName} Endpoint ${endpoint}", isComponent: true, endpointId: endpoint])
    }
    return cd 
}

//child device methods
void componentRefresh(cd){
    def cmds = [
        "he rattr 0x${device.deviceNetworkId} ${cd.data.endpointId} 0x0006 0 {}"
//        zigbee.readAttribute(0x0006, 0x0000, [destEndpoint: cd.data.endpointId])
    ]

    sendCommands(cmds)
}

private sendCommands(List commands) {
	if (commands != null && commands.size() > 0)
	{
		for (String value : commands)
		{
			sendHubCommand([value].collect {new hubitat.device.HubAction(it, hubitat.device.Protocol.ZIGBEE)})
		}
	}
}

def componentPulse(cd){
    if (logEnable) log.info "received on request from ${cd.displayName}"

    componentOn(cd)
    
    runInMillis(pulseLength.toInteger(), 'componentOff', [data: cd])
}

def componentOn(cd){
    def cmd = [
            "he cmd 0x${device.deviceNetworkId} ${cd.data.endpointId} 0x0006 1 {}",
    ]
    sendCommands(cmd)
}

void componentOff(cd){
    def cmd = [
            "he cmd 0x${device.deviceNetworkId} ${cd.data.endpointId} 0x0006 0 {}",
    ]
    sendCommands(cmd)
}

def push(number) {
    childPulse(number)
    
    sendEvent(name:"pushed", value: number)
}

List<String> configure() {
    log.warn "configure..."
    runIn(1800,logsOff)
    //your configuration commands here...
}

def installed() {
    // Set number of endpoints
    sendEvent(name:"numberOfButtons", value: 3)
    
    // Create child devices
    (1..3).each{ fetchChild(it) }
}

def refresh() {
    getChildDevices().each{ componentRefresh(it) }
}
