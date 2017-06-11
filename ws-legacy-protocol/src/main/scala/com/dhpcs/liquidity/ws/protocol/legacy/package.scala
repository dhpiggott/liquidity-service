package com.dhpcs.liquidity.ws.protocol

import play.api.libs.json._

package object legacy {

  final val VersionNumber            = 1L
  final val CompatibleVersionNumbers = Set(1L)

  implicit final lazy val ProtobufStructFormat: OFormat[com.google.protobuf.struct.Struct] = OFormat(
    (jsValue: JsValue) =>
      jsValue
        .validate[Map[String, com.google.protobuf.struct.Value]]
        .map(com.google.protobuf.struct.Struct(_)),
    (struct: com.google.protobuf.struct.Struct) => JsObject(struct.fields.mapValues(Json.toJson(_)))
  )

  implicit final lazy val ProtobufValueFormat: Format[com.google.protobuf.struct.Value] = Format(
    {
      case JsNull =>
        JsSuccess(
          com.google.protobuf.struct.Value(
            com.google.protobuf.struct.Value.Kind.NullValue(com.google.protobuf.struct.NullValue.NULL_VALUE)
          )
        )
      case JsBoolean(value) =>
        JsSuccess(
          com.google.protobuf.struct.Value(
            com.google.protobuf.struct.Value.Kind.BoolValue(value)
          )
        )
      case JsNumber(value) =>
        JsSuccess(
          com.google.protobuf.struct.Value(
            com.google.protobuf.struct.Value.Kind.NumberValue(value.doubleValue())
          )
        )
      case JsString(value) =>
        JsSuccess(com.google.protobuf.struct.Value(com.google.protobuf.struct.Value.Kind.StringValue(value)))
      case jsArray: JsArray =>
        jsArray
          .validate[Seq[com.google.protobuf.struct.Value]]
          .map(
            value =>
              com.google.protobuf.struct.Value(
                com.google.protobuf.struct.Value.Kind
                  .ListValue(com.google.protobuf.struct.ListValue(value))
            ))
      case jsObject: JsObject =>
        jsObject
          .validate[Map[String, com.google.protobuf.struct.Value]]
          .map(
            value =>
              com.google.protobuf.struct.Value(
                com.google.protobuf.struct.Value.Kind
                  .StructValue(com.google.protobuf.struct.Struct(value))
            ))
    },
    _.kind match {
      case com.google.protobuf.struct.Value.Kind.Empty =>
        throw new IllegalArgumentException("Empty Kind")
      case com.google.protobuf.struct.Value.Kind.NullValue(_) =>
        JsNull
      case com.google.protobuf.struct.Value.Kind.NumberValue(numberValue) =>
        JsNumber(numberValue)
      case com.google.protobuf.struct.Value.Kind.StringValue(stringValue) =>
        JsString(stringValue)
      case com.google.protobuf.struct.Value.Kind.BoolValue(boolValue) =>
        JsBoolean(boolValue)
      case com.google.protobuf.struct.Value.Kind.StructValue(structValue) =>
        JsObject(structValue.fields.mapValues(Json.toJson(_)))
      case com.google.protobuf.struct.Value.Kind.ListValue(listValue) =>
        JsArray(listValue.values.map(Json.toJson(_)))
    }
  )

}
