/**
 *  NUT Child UPS Device Type for Hubitat
 *  Péter Gulyás (@guyeeba)
 *
 *  Usage:
 *  See NUT UPS Driver
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
    definition (name: "NUT Child UPS", namespace: "guyee", author: "Péter Gulyás") {
        capability "Refresh"
		capability "TemperatureMeasurement"
		capability "PowerSource"
		
		// Outlet capabilities
		capability "VoltageMeasurement"		
		capability "PowerMeter"
		capability "Battery"
	}
	
	attribute "batteryRuntimeSecs", "Integer"
	attribute "batteryType", "String"

	attribute "deviceManufacturer", "String"
	attribute "deviceModel", "String"
	attribute "deviceType", "String"
	attribute "deviceFirmware", "String"
	attribute "deviceNominalPower", "Integer"
	
	attribute "driverName", "String"
	attribute "driverVersion", "String"
	attribute "driverVersionInternal", "String"
	attribute "driverVersionData", "String"
	
	attribute "load", "Float"
	attribute "status", "String"

	attribute "inputVoltage", "Float"
	
	attribute "outputVoltage", "Float"
	attribute "outputVoltageNominal", "Float"
	attribute "outputFrequency", "Float"
	attribute "outputFrequencyNominal", "Float"

	attribute "outletDescription", "String"
	attribute "outletSwitchable", "Boolean"
}

def refresh() {
    parent.childRefresh(getUPSName())
}

def parseVAR(String[] msg) {
	def key = msg[0].split('\\.', -1)
	def value = msg.length > 1 ? msg[1] : null
	
	switch (key[0]) {
		case "battery":
			parseBATTERY(key.drop(1), value)
			break
		case "device":
			parseDEVICE(key.drop(1), value)
			break
		case "driver":
			parseDRIVER(key.drop(1), value)
			break
		case "ups":
			parseUPS(key.drop(1), value)
			break
		case "input":
			parseINPUT(key.drop(1), value)
			break
		case "output":
			parseOUTPUT(key.drop(1), value)
			break
		case "outlet":
			parseOUTLET(key.drop(1), value)
			break
		default:
			log.error("ParseVAR: Couldn't process message: \"${msg}\"")
	}
}

def parseBATTERY(String[] msg, String value) {
	switch (msg[0]) {
		case "charge":
			sendEvent( [
				name: 'battery',
				value: Float.parseFloat(value),
				unit: "%",
				descriptionText: "Battery is at ${Float.parseFloat(value)}%"
			])
			break;
		case "runtime":
			sendEvent( [
				name: 'batteryRuntimeSecs',
				value: Integer.parseInt(value),
				unit: "sec",
				descriptionText: "Remaining runtime is ${Integer.parseInt(value)} seconds"
			])
			break;
		case "type":
			sendEvent( [
				name: 'batteryType',
				value: value,
				descriptionText: "Battery type is ${value}"
			])
			break;
		default:
			log.error("ParseBATTERY: Couldn't process message: \"${msg}\"")
	}
}

def parseDEVICE(String[] msg, String value) {
	switch (msg[0]) {
		case "mfr":
			sendEvent( [
				name: 'deviceManufacturer',
				value: value,
				descriptionText: "Device manufacturer is ${value}"
			])
			break;
		case "type":
			sendEvent( [
				name: 'deviceType',
				value: value,
				descriptionText: "Device type is ${value}"
			])
			break;
		case "model":
			sendEvent( [
				name: 'deviceModel',
				value: value,
				descriptionText: "Device model is ${value}"
			])
			break;
		default:
			log.error("ParseDEVICE: Couldn't process message: \"${msg}\"")
	}
}

def parseDRIVER(String[] msg, String value) {
	switch (msg[0]) {
		case "name":
			sendEvent( [
				name: 'driverName',
				value: value,
				descriptionText: "Driver name is ${value}"
			])
			break;
		case "version":
			if (msg.length == 1) {
				sendEvent( [
					name: 'driverVersion',
					value: value,
					descriptionText: "Driver version is ${value}"
				])
			}
			else {
				parseDRIVER_VERSION(msg.drop(1), value)
			}
			break;
		case "parameter": // Not really interesting
			break;
		default:
			log.error("ParseDEVICE: Couldn't process message: \"${msg}\"")
	}
}

def parseDRIVER_VERSION(String[] msg, String value) {
	switch (msg[0]) {
		case "internal":
			sendEvent( [
				name: 'driverVersionInternal',
				value: value,
				descriptionText: "Driver internal version is ${value}"
			])
			break;
		case "data":
			sendEvent( [
				name: 'driverVersionData',
				value: value,
				descriptionText: "Driver version data is ${value}"
			])
			break;
		default:
			log.error("ParseDEVICE: Couldn't process message: \"${msg}\"")
	}
}

def parseUPS(String[] msg, String value) {
	switch (msg[0]) {
		case "temperature":
			sendEvent( [
				name: 'temperature',
				value: Float.parseFloat(value),
				unit: "°C",
				descriptionText: "Temperature is ${Float.parseFloat(value)}°C"
			])
			break;
		case "load":
			sendEvent( [
				name: 'load',
				value: Float.parseFloat(value),
				unit: "%",
				descriptionText: "UPS load is ${Float.parseFloat(value)}%"
			])
			break;
		case "firmware":
			sendEvent( [
				name: 'deviceFirmware',
				value: value,
				descriptionText: "Device firmware version is ${value}"
			])
			break;
		case "mfr":
			sendEvent( [
				name: 'deviceManufacturer',
				value: value,
				descriptionText: "Device manufacturer is ${value}"
			])
			break;
		case "model":
			sendEvent( [
				name: 'deviceModel',
				value: value,
				descriptionText: "Device model is ${value}"
			])
			break;
		case "status":
			def statusCodeMap = [
				'OL': 'Online',
				'OB': 'On Battery',
				'LB': 'Low Battery',
				'HB': 'High Battery',
				'RB': 'Battery Needs Replaced',
				'CHRG': 'Battery Charging',
				'DISCHRG': 'Battery Discharging',
				'BYPASS': 'Bypass Active',
				'CAL': 'Runtime Calibration',
				'OFF': 'Offline',
				'OVER': 'Overloaded',
				'TRIM': 'Trimming Voltage',
				'BOOST': 'Boosting Voltage',
				'FSD': 'Forced Shutdown'
			]
			def statuses = value.split(" ")
			String statusText = statuses?.collect { statusCodeMap[it] }.join(", ")

			sendEvent( [
				name: 'status',
				value: statusText,
				descriptionText: "Device status is ${statusText}"
			])

			def powersource = "unknown"
			if (statuses.contains('OL'))
				powersource = "mains"
			else if (statuses.contains('OB'))
				powersource = "battery"

			sendEvent( [
				name: 'powerSource',
				value: powersource,
				descriptionText: "Power source is ${powersource}"
			])

			break;
		case "vendorid": // Not really interesting
			break;
		case "timer": // Not really interesting
			break;
		case "start": // Not really interesting
			break;
		case "productid": // Not really interesting
			break;
		case "delay": // Not really interesting
			break;
		case "beeper": // Not really interesting
			break;
		case "power":
			if (msg.length > 1 && msg[1] == "nominal") {
				sendEvent( [
					name: 'deviceNominalPower',
					value: Integer.parseInt(value),
					unit: "VA",
					descriptionText: "Device nominal power is ${Integer.parseInt(value)}VA"
				])
			}
			break;
		default:
			log.error("ParseUPS: Couldn't process message: \"${msg} - ${value}\"")
	}
}

def parseINPUT(String[] msg, String value) {
	switch (msg[0]) {
		case "voltage":
			sendEvent( [
				name: 'inputVoltage',
				value: Float.parseFloat(value),
				unit: "V",
				descriptionText: "Input voltage is ${Float.parseFloat(value)}V"
			])
			break;
		default:
			log.error("ParseINPUT: Couldn't process message: \"${msg}\"")
	}
}

def parseOUTPUT(String[] msg, String value) {
	switch (msg[0]) {
		case "voltage":
			if (msg.length > 1 && msg[1] == "nominal") {
				sendEvent( [
					name: 'outputVoltageNominal',
					value: Float.parseFloat(value),
					unit: "V",
					descriptionText: "Nominal output voltage is ${Float.parseFloat(value)}V"
				])
			} else if (msg.length == 1) {
				sendEvent( [
					name: 'outputVoltage',
					value: Float.parseFloat(value),
					unit: "V",
					descriptionText: "Output voltage is ${Float.parseFloat(value)}V"
				])
			} else {
				log.error("ParseOUTPUT: Couldn't process message: \"${msg}\"")
			}
			break;
		case "frequency":
			if (msg.length > 1 && msg[1] == "nominal") {
				sendEvent( [
					name: 'outputFrequencyNominal',
					value: Float.parseFloat(value),
					unit: "Hz",
					descriptionText: "Nominal output frequency is ${Float.parseFloat(value)}Hz"
				])
			} else if (msg.length == 1) {
				sendEvent( [
					name: 'outputFrequency',
					value: Float.parseFloat(value),
					unit: "Hz",
					descriptionText: "Output frequency is ${Float.parseFloat(value)}Hz"
				])
			} else {
				log.error("ParseOUTPUT: Couldn't process message: \"${msg}\"")
			}
			break;
		default:
			log.error("ParseOUTPUT: Couldn't process message: \"${msg}\"")
	}
}

def parseOUTLET(String[] msg, String value) {
	switch (msg[0]) {
		case "id":
		break;
		case "desc":
			sendEvent( [
				name: 'outletDescription',
				value: value,
				descriptionText: "Outlet description is ${value}"
			])
			break;
		case "switchable":
			Boolean isSwitchable = (value == "yes")
			sendEvent( [
				name: 'outletSwitchable',
				value: isSwitchable,
				descriptionText: "Outlet is ${isSwitchable ? "" : "not"} switchable"
			])
			break;			
		default:
			if (msg[0].isNumber()) {
				def outletId = msg[0].toInteger()
				def childOutlet = getChildDevice(getOutletDNID(outletId))
				
				if (childOutlet == null) {
					log.debug "Outlet ${outletId} not found, creating"
					childOutlet = addChildDevice("guyee", "NUT Child UPS Outlet", getOutletDNID(outletId), [name: "Outlet ${outletId}", label: "Outlet ${outletId}", completedSetup: true, isComponent: false ])
				}
				
				childOutlet.parseOUTLET(msg.drop(1), value)
			} else {
				log.error("ParseOUTLET: Couldn't process message: \"${msg}\"")
			}
	}
}

def getUPSName() {
	device.deviceNetworkId.substring(device.deviceNetworkId.lastIndexOf("-") + 1)
}

def getOutletDNID(Integer id) {
	return "${device.deviceNetworkId}-outlet${id}"
}
