/*
 *  Xiaomi Aqara Wireless Wall Button (WXKG02LM and WXKG03LM)
 *  Device Driver for Hubitat Elevation hub
 *
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
 *  With contributions by alecm, alixjg, bspranger, gn0st1c, foz333, jmagnuson, rinkek, ronvandegraaf, snalee, tmleafs, twonk, & veeceeoh
 *
 *
 *  https://xiaomi-mi.com/sockets-and-sensors/xiaomi-aqara-smart-light-control-set
 */

metadata {
	definition (name: "Aqara Wall Button (WXKG02LM, WXKG03LM)", namespace: "guyee", author: "Péter GULYÁS") {
		capability "PushableButton"
		capability "Battery"
		capability "Sensor"
		capability "Temperature Measurement"

		attribute "lastCheckin", "String"
		attribute "batteryLastReplaced", "String"
		attribute "buttonPressed", "String"

		// WXKG02LM
		fingerprint endpointId: "01", profileId: "0104", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sensor_86sw2Un"
		fingerprint endpointId: "01", profileId: "0104", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sensor_86sw2\u0001\u0000 \u0004"
		
		// WXKG03LM
		fingerprint endpointId: "01", profileId: "0104", inClusters: "0000,0003,0019,FFFF,0012", outClusters: "0000,0004,0003,0005,0019,FFFF,0012", manufacturer: "LUMI", model: "lumi.sensor_86sw1\u0001\u0000 \u0005"

		command "resetBatteryReplacedDate"
	}

	preferences {
		//Battery Voltage Range
 		input name: "voltsmin", title: "Min Volts (0% battery = ___ volts, range 2.0 to 2.7). Default = 2.5 Volts", description: "", type: "decimal", range: "2..2.7"
 		input name: "voltsmax", title: "Max Volts (100% battery = ___ volts, range 2.8 to 3.4). Default = 3.0 Volts", description: "", type: "decimal", range: "2.8..3.4"
 		//Logging Message Config
		input name: "infoLogging", type: "bool", title: "Enable info message logging", description: ""
		input name: "debugLogging", type: "bool", title: "Enable debug message logging", description: ""
	}
}

// Parse incoming device messages to generate events
def parse(String description) {
    def raw = description.split(",").find {it.split(":")[0].trim() == "read attr - raw"}?.split(":")[1].trim()
    def endpoint = description.split(",").find {it.split(":")[0].trim() == "endpoint"}?.split(":")[1].trim()
	def cluster	= description.split(",").find {it.split(":")[0].trim() == "cluster"}?.split(":")[1].trim()
	def attrId = description.split(",").find {it.split(":")[0].trim() == "attrId"}?.split(":")[1].trim()
	def valueHex = description.split(",").find {it.split(":")[0].trim() == "value"}?.split(":")[1].trim()
	def map = []

	displayDebugLog("Parsing message: ${description}")

	// lastCheckin can be used with webCoRE
	sendEvent(name: "lastCheckin", value: now())

	// Send message data to appropriate parsing function based on the type of report
	if (cluster == "0006") {
		// Parse button press: endpoint 01 = left, 02 = right, 03 = both
		map += parseButtonPress(Integer.parseInt(endpoint))
    } else if (cluster == "0000") {
// Unfortunately the following line causes exception, because Xiaomi reports wrong message length... dammit...
//	    def descMap = zigbee.parseDescriptionAsMap(description)
        def prefixLen = 4 + 2 + 4  // dni + endpoint + cluster
        def msgLen = Integer.parseInt(raw[prefixLen..(prefixLen + 1)], 16)
        map += parseXiaomiReport(raw[(prefixLen + 2)..(prefixLen + 2 + msgLen - 1)])
    } else {
		displayDebugLog("Unable to parse message")
	}
    
    return map
}

// Build event map based on type of button press
private Map parseButtonPress(value) {
	def pushType = ["", "Left", "Right", "Both"]
	def descText = "${pushType[value]} button${(value == 3) ? "s" : ""} pressed (Button $value pushed)"
	displayInfoLog(descText)
	displayDebugLog("Setting buttonPressed to current date/time for webCoRE")
	sendEvent(name: "buttonPressed", value: now(), descriptionText: "Updated buttonPressed (webCoRE)")
	return [
		name: 'pushed',
		value: value,
		isStateChange: true,
		descriptionText: descText
	]
}

private def displayDebugLog(message) {
	if (debugLogging) log.debug "${device.displayName}: ${message}"
}

private def displayInfoLog(message) {
	if (infoLogging || state.prefsSetCount != 1)
		log.info "${device.displayName}: ${message}"
}

//Reset the batteryLastReplaced date to current date
def resetBatteryReplacedDate(paired) {
	def newlyPaired = paired ? " for newly paired sensor" : ""
	sendEvent(name: "batteryLastReplaced", value: new Date())
	displayInfoLog("Setting Battery Last Replaced to current date${newlyPaired}")
}

// this call is here to avoid Groovy errors when the Push command is used
// it is empty because the Xioami button is non-controllable
def push() {
	displayDebugLog("No action taken on Push Command. This button cannot be controlled.")
}

// installed() runs just after a sensor is paired
def installed() {
	state.prefsSetCount = 0
	displayInfoLog("Installing")
}

// configure() runs after installed() when a sensor is paired or reconnected
def configure() {
	displayInfoLog("Configuring")
	init()
	displayInfoLog("Number of buttons = 3")
	state.prefsSetCount = 1
	return
}

// updated() runs every time user saves preferences
def updated() {
	displayInfoLog("Updating preference settings")
	init()
	displayInfoLog("Info message logging enabled")
	displayDebugLog("Debug message logging enabled")
}

def init() {
	if (!device.currentState('batteryLastReplaced')?.value)
		resetBatteryReplacedDate(true)
	sendEvent(name: "numberOfButtons", value: 3)
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
        
        if (dataLen == null || dataLen == -1) { // Probably variable length
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
            displayDebugLog("WTF Xiaomi, packet length received from you (${dataLen} bytes) is greater than the length of remaining data (${(msgLength - msgPos) / 2} bytes)!")
        	dataLen = (msgLength - msgPos) / 2
        }

        def dataPayload

        if (dataLen != 0)
        	dataPayload = description[msgPos++..(msgPos+=(dataLen * 2) - 1)-1]

        switch (attrId) {
            case 0xFF01:
             	manufacturerSpecificValues = parseXiaomiReport_FF01(dataPayload)
            	displayInfoLog(manufacturerSpecificValues);
               	break;
            case 0xFFF0: // reset
             	manufacturerSpecificValues = parseXiaomiReport_FFF0(dataPayload)
            	displayInfoLog(manufacturerSpecificValues);
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

    if (manufacturerSpecificValues?.containsKey("Power")) {
        events += [
			name: 'power',
			value: manufacturerSpecificValues["Power"],
			unit: "W",
			isStateChange: true,
			descriptionText: "Actual power consumption is ${manufacturerSpecificValues["Power"]}W"
		]
    }

    if (manufacturerSpecificValues?.containsKey("Energy")) {
        events += [
			name: 'energy',
			value: manufacturerSpecificValues["Energy"],
			unit: "kWh",
			isStateChange: true,
			descriptionText: "Power consumption so far is ${manufacturerSpecificValues["Energy"]}kWh"
		]
    }

	if (manufacturerSpecificValues?.containsKey("RouterID")) {
		state.routerID = manufacturerSpecificValues["RouterID"].toUpperCase()
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

        if (dataLen == null || dataLen == -1) { // Probably variable length
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
//		        log.warn("RSSI: dataTag = 0x${Integer.toHexString(dataTag)}, type = 0x${Integer.toHexString(dataType)}, length = ${dataLen} bytes, payload = ${dataPayload}")
            	values += [ RSSI : (toBigEndian(dataPayload) / 10) - 90 ]
            	break;
            case 0x06: // LQI
            	values += [ LQI : 255 - toBigEndian(dataPayload) ]
            	break;
            case 0x0A: // router
            	values += [ RouterID : Integer.toHexString(toBigEndian(dataPayload)) ]
            	break;
            case 0x64: // switch 1 state
            	values += [ Switch1State : toBigEndian(dataPayload) ]
            	break;
            case 0x65: // switch 2 state
            	values += [ Switch2State : toBigEndian(dataPayload) ]
            	break;
			case 0x95: // energy
				long theValue = Long.parseLong(toBigEndianHexString(dataPayload), 16)
				float floatValue = Float.intBitsToFloat(theValue.intValue());
            	values += [ Energy : floatValue ]
            	break;
			case 0x98: // power
				long theValue = Long.parseLong(toBigEndianHexString(dataPayload), 16)
				float floatValue = Float.intBitsToFloat(theValue.intValue());
            	values += [ Power : floatValue ]
            	break;
            default:
		        log.warn("Unsupported tag in Xiaomi Report msg: dataTag = 0x${Integer.toHexString(dataTag)}, type = 0x${Integer.toHexString(dataType)}, length = ${dataLen} bytes, payload = ${dataPayload}")

        }
    }
    
    return values;
}

private parseXiaomiReport_FFF0(payload) {
	log.warn("Xiaomi parse FFF0 string unimplemented: ${payload}")
	
	return null
}

private parseXiaomiReport_0005(payload) {
	displayDebugLog("Xiaomi parse 0005 string = ${payload}")
    
    return new String(payload.decodeHex())
}
