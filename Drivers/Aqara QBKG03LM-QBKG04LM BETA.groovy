metadata {
    definition (name: "Aqara Wall Switch (multi-endpoint)", namespace: "guyee", author: "Péter Gulyás") {
        capability "Configuration"
        capability "Refresh"
		capability "PushableButton"
		capability "HoldableButton"
		capability "ReleasableButton"

        command "childOn"
        command "childOff"
        command "childRefresh"
        command "recreateChildDevices"
        command "deleteChildren"
        // two buttons, no neutral required
        fingerprint profileId: "0104", inClusters: "0000,0003,0001,0002,0019,000A", outClusters: "0000,000A,0019", manufacturer: "LUMI", model: "lumi.ctrl_neutral1", deviceJoinName: "Aqara Wall switch"
    }

    preferences {
        input name: "numButtons", type: "enum", description: "", title: "Number of buttons", options: [[1:"1"],[2:"2"]], defaultValue: 1
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

def logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

def childRefresh(String deviceId)
{
    def endpointId = endpointNumber(deviceId);
    return  [
            "he rattr 0x${device.deviceNetworkId} ${endpointId} 0x0006 0 {}","delay 200",  //light state
    ]
}

def refresh() {
    log.debug numButtons
    def cmds = []
    def children = getChildDevices()
    children?.each{
        cmds += childRefresh(it.deviceNetworkId)
    }
    return cmds
}

def parse(String description) {
    log.debug description
    
    if (description.startsWith("catchall"))
    	return
    
    def events = []

    def cluster = Integer.parseInt(description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim(), 16)
    def endpoint = Integer.parseInt(description.split(",").find {it.split(":")[0].trim() == "endpoint"}?.split(":")[1].trim(), 16)
	def attrId = Integer.parseInt(description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim(), 16)
	def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
    
    if (cluster == 0x0006) { // on/off
        if (isSwitchEP(endpoint)) {
            if (attrId == 0x0000) {
			    def childDevice = getChildDevice("${device.zigbeeId}-${endpoint}")

                def switchState
                if (Integer.parseInt(valueHex[0..1], 16))
                	switchState = "on"
                else
                    switchState = "off"
                
                childDevice.sendEvent(name:"switch", value:switchState, descriptionText:"Switch #${EPtoIndex(endpoint)} has been turned $switchState")
	        } else {
	            log.warn("Unknown attribute $attrId for endpoint $endpoint")
            }
        } else if (isButtonEP(endpoint)) {
            if (attrId == 0x0000) {
                def messageMap = [
                    "00": "held",
                    "0000001001": "single-clicked",
                    "01": "released"
                    ]
                def eventTypeMap = [
                    "00": "held",
                    "0000001001": "pushed",
                    "01": "released"
                    ]
                
                events += [
                    name: eventTypeMap[valueHex],
                    value: EPtoIndex(endpoint),
                    isStateChange: true,
                    descriptionText: "Button was ${messageMap[valueHex]}"
                ]
	        } else {
	            log.warn("Unknown attribute $attrId for endpoint $endpoint")
            }
        } else {
            log.warn("Unknown endpoint $endpoint")
        }
    } else if (cluster == 0x0000 && attrId == 0x0000) {
	    def descMap = zigbee.parseDescriptionAsMap(description)
        def prefixLen = 4 + 2 + 4  // dni + endpoint + cluster
        def msgLen = Integer.parseInt(descMap.raw[prefixLen..(prefixLen + 1)], 16)
        events += parseXiaomiReport(descMap.raw[(prefixLen + 2)..(descMap.raw.length() - 1)])
    } else {
        log.info("Unknown message: $description")
    }
	
    return events
}

private int toBigEndian(String hex) {
    int ret = 0;
    String hexBigEndian = "";
    if (hex.length() % 2 != 0) return ret;
    for (int i = hex.length() - 2; i >= 0; i -= 2) {
        hexBigEndian += hex.substring(i, i + 2);
    }
    ret = Integer.parseInt(hexBigEndian, 16);
    return ret;
}

private parseXiaomiReport(description) {
	displayDebugLog("Xiaomi parse string = ${description}")
	def msgPos = 0
	def msgLength = description.size()

    def modelId = null;
    def manufacturerSpecificValues = null;
    
    while (msgPos < msgLength) {
    	def attrId = toBigEndian(description[msgPos++..(msgPos+=3)-1])
        def dataType = Integer.parseInt(description[msgPos++..msgPos++], 16)
        def dataLen = DataType.getLength(dataType)
        
        if (dataLen == null) { // Probably variable length
            switch (dataType) {
                case DataType.STRING_OCTET:
                case DataType.STRING_CHAR:
			        dataLen = Integer.parseInt(description[msgPos++..msgPos++], 16)
                	break;
                case DataType.STRING_LONG_OCTET:
                case DataType.STRING_LONG_CHAR:
			        dataLen = toBigEndian(description[msgPos++..(msgPos+=3)-1])
                	break;
	            default:
    	            log.error("Unsupported data type in Xiaomi Report msg: attrID = 0x${Integer.toHexString(attrId)}, type = 0x${Integer.toHexString(dataType)}")
        	        return
            }
        }
        
        if (dataLen * 2 > msgLength - msgPos) {  // Yes, it happens with lumi.sensor_86sw2 (WXKG02LM)
            log.error("WTF Xiaomi, packet length received from you (${dataLen} bytes) is greater than the length of remaining data (${(msgLength - msgPos) / 2} bytes)!")
        	dataLen = (msgLength - msgPos) / 2
        }

        def dataPayload

        if (dataLen != 0)
        	dataPayload = description[msgPos++..(msgPos+=(dataLen * 2) - 1)-1]

        switch (attrId) {
            case 0xFF01:
             	manufacturerSpecificValues = parseXiaomiReport_FF01(dataPayload)
            	log.warn(manufacturerSpecificValues);
               	break;
            case 0x0005:
             	modelId = parseXiaomiReport_0005(dataPayload)
               	break;
            default:
                log.warn("Unsupported attribute in Xiaomi Report msg: attrID = 0x${Integer.toHexString(attrId)}, type = 0x${Integer.toHexString(dataType)}, length = ${dataLen} bytes, payload = ${dataPayload}")
        }
    }

    def events = []
    
    if (manufacturerSpecificValues.containsKey("BatteryPct")) {
        events += [
			name: 'battery',
			value: manufacturerSpecificValues["BatteryPct"],
			unit: "%",
			isStateChange: true,
			descriptionText: "Battery level is ${manufacturerSpecificValues["BatteryPct"]}% (${manufacturerSpecificValues["BatteryVolts"]} Volts)"
		]
    }

    if (manufacturerSpecificValues.containsKey("Temperature")) {
        events += [
			name: 'temperature',
			value: manufacturerSpecificValues["Temperature"],
			unit: "°C",
			isStateChange: true,
			descriptionText: "Temperature is ${manufacturerSpecificValues["Temperature"]}°C"
		]
    }

    return events
}

private parseXiaomiReport_FF01(payload) {
	displayDebugLog("Xiaomi parse FF01 string = ${payload}")
    
    def values = [ : ]

    def msgPos = 0
	def msgLength = payload.size()

    while (msgPos < msgLength) {
        def dataTag = Integer.parseInt(payload[msgPos++..msgPos++], 16)
        def dataType = Integer.parseInt(payload[msgPos++..msgPos++], 16)
        def dataLen = DataType.getLength(dataType)

        if (dataLen == null) { // Probably variable length
            switch (dataType) {
                case DataType.STRING_OCTET:
                case DataType.STRING_CHAR:
			        dataLen = Integer.parseInt(payload[msgPos++..msgPos++], 16)
                	break;
                case DataType.STRING_LONG_OCTET:
                case DataType.STRING_LONG_CHAR:
			        dataLen = toBigEndian(payload[msgPos++..(msgPos+=3)-1])
                	break;
	            default:
    	            log.error("Unsupported data type in Xiaomi Report msg: attrID = 0x${Integer.toHexString(attrId)}, type = 0x${Integer.toHexString(dataType)}")
        	        return
            }
        }

        def dataPayload

        if (dataLen != 0)
        	dataPayload = payload[msgPos++..(msgPos+=(dataLen * 2) - 1)-1]
        
        switch (dataTag) {
            case 0x01: // Battery
                def rawVolts = toBigEndian(dataPayload) / 1000
				def minVolts = voltsmin ? voltsmin : 2.5
				def maxVolts = voltsmax ? voltsmax : 3.0
				def pct = (rawVolts - minVolts) / (maxVolts - minVolts)
				def roundedPct = Math.min(100, Math.round(pct * 100))

            	values += [ BatteryPct : roundedPct ]
            	values += [ BatteryVolts : rawVolts ]
            	break;
            case 0x03: // Temperature
            	values += [ Temperature : Integer.parseInt(dataPayload, 16) - 8 ]  // Just a guess :)
            	break;
            case 0x05: // RSSI
            	values += [ RSSI : toBigEndian(dataPayload) ]
            	break;
            case 0x06: // LQI
            	values += [ LQI : toBigEndian(dataPayload) ]
            	break;
            case 0x0A: // router
            	values += [ RouterID : Integer.toHexString(toBigEndian(dataPayload)) ]
            	break;
            default:
		        log.warn("Unsupported tag in Xiaomi Report msg: dataTag = 0x${Integer.toHexString(dataTag)}, type = 0x${Integer.toHexString(dataType)}, length = ${dataLen} bytes, payload = ${dataPayload}")
        }
    }
    
    return values;
}

private parseXiaomiReport_0005(payload) {
	displayDebugLog("Xiaomi parse 0005 string = ${payload}")
    
    return new String(payload.decodeHex())
}

def childOn(String deviceId) {
    def endpointId = endpointNumber(deviceId)
    def cmd = [
            "he cmd 0x${device.deviceNetworkId} ${endpointId} 0x0006 1 {}",
            "he rattr 0x${device.deviceNetworkId} ${endpointId} 0x0006 0 {}","delay 200",  //light state
    ]
    return cmd
}

def childOff(String deviceId) {
    def endpointId = endpointNumber(deviceId)
    def cmd = [
            "he cmd 0x${device.deviceNetworkId} ${endpointId} 0x0006 0 {}",
            "he rattr 0x${device.deviceNetworkId} ${endpointId} 0x0006 0 {}","delay 200",  //light state
    ]
    return cmd
}

def configure() {
    log.warn "configure..."
    runIn(1800,logsOff)
    def cmds = []
    def children = getChildDevices()
    children?.each{
        def endpointId = endpointNumber(it.deviceNetworkId)
	    cmds += [
            //bindings
            "zdo bind 0x${device.deviceNetworkId} ${endpointId} 1 0x0006 {${device.zigbeeId}} {}", "delay 200",
            //reporting
            "he cr 0x${device.deviceNetworkId} ${endpointId} 0x0006 0 0x10 0 0x3600 {}","delay 200",
    	]
    }
    cmds += refresh()
    
	sendEvent(name:"numberOfButtons", value: numButtons)

    return cmds
}

def installed() {
    createChildDevices()
	configure()
}

def updated() {
    log.info "updated..."
    log.warn "debug logging is: ${logEnable == true}"
    log.warn "description logging is: ${txtEnable == true}"
    if (logEnable) runIn(1800,logsOff)
    
    if (!childDevices) {
        createChildDevices()
    }
    else if (device.label != state.oldLabel) {
        childDevices.each {
            def newLabel = "$device.displayName (EP${endpointNumber(it.deviceNetworkId)})"
            it.setLabel(newLabel)
        }
  
        state.oldLabel = device.label
    }

    configure()
}

def recreateChildDevices() {
    log.debug "recreateChildDevices"
    deleteChildren()
    createChildDevices()
}

def deleteChildren() {
	log.debug "deleteChildren"
	def children = getChildDevices()
    
    children.each {child->
  		deleteChildDevice(child.deviceNetworkId)
    }
}

private void createChildDevices() {
    log.debug "createChildDevices"
    
    for (i in 1..numButtons.toInteger()) {
        def switchEPId = indexToSwitchEP(i)
        addChildDevice("guyee", "Generic Child Switch", "$device.zigbeeId-$switchEPId", [name: "EP$switchEPId", label: "$device.displayName $switchEPId", isComponent: false])
    }
}

def intTo16bitUnsignedHex(value) {
    def hexStr = zigbee.convertToHexString(value.toInteger(),4)
    return new String(hexStr.substring(2, 4) + hexStr.substring(0, 2))
}

def intTo8bitUnsignedHex(value) {
    return zigbee.convertToHexString(value.toInteger(), 2)
}

private endpointNumber(String deviceId) {
    return deviceId.split("-")[-1] as Integer
}

private int indexToSwitchEP(int index) {
    return 1 + index
}

private int indexToButtonEP(int index) {
    return 3 + index
}

private int buttonToSwitchEP(int EP) {
    return EP - 2
}

private int switchToButtonEP(int EP) {
    return EP + 2
}

private boolean isSwitchEP(int EP) {
    return (2..3).contains(EP)
}

private boolean isButtonEP(int EP) {
    return (4..5).contains(EP)
}

private int EPtoIndex(int EP)
{
    return (EP % 2) + 1
}

private def displayDebugLog(message) {
	if (debugLogging) log.debug "${device.displayName}: ${message}"
}
