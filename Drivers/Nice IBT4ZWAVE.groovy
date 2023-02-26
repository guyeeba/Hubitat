/**
 *  Nice IBT4ZWAVE (BiDi-ZWave) Device Driver for Hubitat
 *  Péter Gulyás (@guyeeba)
 *
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

import groovy.transform.Field

@Field static Map commandClassVersions = [
        0x20: 2     //basic
        ,0x86: 3    //version
        ,0x26: 4    //switchMultiLevel
        ,0x85: 2    //association
        ,0x71: 8    //notification
]
@Field static Map switchLevelValues = ["636300":"open", "FE63FE": "opening", "FEFE00": "unknown", "FE00FE": "closing", "000000": "closed"]

metadata {
    definition (name: "Nice IBT4ZWAVE Garage Door Opener",namespace: "guyee", author: "Peter Gulyas") {
        capability "Actuator"
        capability "Refresh"
        capability "GarageDoorControl"
        
        attribute "firmwareVersion", "string"
        attribute "protocolVersion", "string"
        attribute "hardwareVersion", "string"

        fingerprint deviceId: "4096", inClusters: "0x5E,0x98,0x9F,0x6C,0x55,0x22", mfr: "1089", prod: "9216", deviceJoinName: "Nice IBT4ZWAVE"
    }

    preferences {
        input name: "logEnable", type: "bool", title: "Enable debug logging", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
    }
}

void logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

void parse(String description){
    if (logEnable) log.debug "parse description: ${description}"
    hubitat.zwave.Command cmd = zwave.parse(description,commandClassVersions)
    if (cmd) {
        zwaveEvent(cmd)
    }
}

String secure(String cmd){
    return zwaveSecureEncap(cmd)
}

String secure(hubitat.zwave.Command cmd){
    return zwaveSecureEncap(cmd)
}

void zwaveEvent(hubitat.zwave.commands.versionv3.VersionReport cmd) {
    Double firmware0Version = cmd.firmware0Version + (cmd.firmware0SubVersion / 100)
    Double protocolVersion = cmd.zWaveProtocolVersion + (cmd.zWaveProtocolSubVersion / 100)
    if (txtEnable) log.info "Version Report - FirmwareVersion: ${firmware0Version}, ProtocolVersion: ${protocolVersion}, HardwareVersion: ${cmd.hardwareVersion}"
    sendEvent(name: "firmwareVersion", value: firmware0Version)
    sendEvent(name: "protocolVersion", value: protocolVersion)
    sendEvent(name: "hardwareVersion", value: cmd.hardwareVersion)
}

void zwaveEvent(hubitat.zwave.commands.supervisionv1.SupervisionGet cmd){
    hubitat.zwave.Command encapCmd = cmd.encapsulatedCommand(commandClassVersions)
    if (encapCmd) {
        zwaveEvent(encapCmd)
    }
    sendHubCommand(new hubitat.device.HubAction(secure(zwave.supervisionV1.supervisionReport(sessionID: cmd.sessionID, reserved: 0, moreStatusUpdates: false, status: 0xFF, duration: 0)), hubitat.device.Protocol.ZWAVE))
}

void zwaveEvent(hubitat.zwave.commands.switchmultilevelv4.SwitchMultilevelReport cmd){
    if (logEnable) log.debug "SwitchMultilevelReport value: ${cmd.value}, targetValue: ${cmd.targetValue}, duration: ${cmd.duration}"
    String doorState = switchLevelValues[hubitat.helper.HexUtils.byteArrayToHexString((byte[])cmd.payload.toArray())];
    doorText = "${device.displayName} is ${doorState}"
    if (txtEnable) log.info "${doorText}"
    sendEvent(name: "door", value: doorState, descriptionText: doorText, type: "physical")
}

void zwaveEvent(hubitat.zwave.Command cmd){
    log.info "skip: ${cmd}"
}

List<String> open(){
    if (txtEnable) log.info "Sending Open command to ${device.displayName}"

    return [
            secure(zwave.switchMultilevelV4.switchMultilevelSet(value: 0xFF, dimmingDuration: 0x00))
            ,"delay 500"
            ,secure(zwave.switchMultilevelV4.switchMultilevelGet())
    ]
}

List<String> close(){
    if (txtEnable) log.info "Sending Close command to ${device.displayName}"

    return [
            secure(zwave.switchMultilevelV4.switchMultilevelSet(value: 0x00, dimmingDuration: 0x00))
            ,"delay 500"
            ,secure(zwave.switchMultilevelV4.switchMultilevelGet())
    ]
}

List<String> refresh(){
    if (logEnable) log.debug "refresh"
    state.bin = -2
    return [
            secure(zwave.versionV3.versionGet())
            ,"delay 500"
            ,secure(zwave.switchMultilevelV4.switchMultilevelGet())
    ]
}

void installed(){
    log.warn "installed..."
    runIn(5, "refresh")
}

void configure(){
    log.warn "configure..."
    runIn(1800,logsOff)
    runIn(5, "refresh")
}

//capture preference changes
List<String> updated(){
    log.info "updated..."
    log.warn "debug logging is: ${logEnable == true}"
    log.warn "description logging is: ${txtEnable == true}"
    if (logEnable) runIn(1800,logsOff)
    
    runIn(5, "refresh")
}