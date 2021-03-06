package cool.graph.client

import cool.graph.DataItem
import cool.graph.client.database.DataResolver
import cool.graph.shared.models.{Model, Project, Relation}
import cool.graph.shared.models.TypeIdentifier.TypeIdentifier
import spray.json.{DefaultJsonProtocol, JsArray, JsBoolean, JsFalse, JsNull, JsNumber, JsObject, JsString, JsTrue, JsValue, JsonFormat, RootJsonFormat}

package object ImportExport {

  case class ExportRequest(fileType: String, cursor: Cursor)      //{"fileType":"nodes","cursor":{"table":INT,"row":INT,"field":INT,"array":INT}}
  case class Cursor(table: Int, row: Int, field: Int, array: Int) //{"table":INT,"row":INT,"field":INT,"array":INT}
  case class ResultFormat(out: JsonBundle, cursor: Cursor, isFull: Boolean)
  case class ImportBundle(valueType: String, values: JsArray)
  case class ImportIdentifier(typeName: String, id: String)
  case class ImportRelationSide(identifier: ImportIdentifier, fieldName: String)
  case class ImportNode(identifier: ImportIdentifier, values: Map[String, Any])
  case class ImportRelation(left: ImportRelationSide, right: ImportRelationSide)
  case class ImportList(identifier: ImportIdentifier, values: Map[String, Vector[Any]])
  case class JsonBundle(jsonElements: Vector[JsValue], size: Int)

  sealed trait ExportInfo {
    val cursor: Cursor
    val hasNext: Boolean
    def rowPlus(increase: Int): ExportInfo = this match {
      case info: NodeInfo     => info.copy(cursor = info.cursor.copy(row = info.cursor.row + increase))
      case info: ListInfo     => info.copy(cursor = info.cursor.copy(row = info.cursor.row + increase))
      case info: RelationInfo => info.copy(cursor = info.cursor.copy(row = info.cursor.row + increase))
    }

    def cursorAtNextModel: ExportInfo = this match {
      case info: NodeInfo     => info.copy(cursor = info.cursor.copy(table = info.cursor.table + 1, row = 0))
      case info: ListInfo     => info.copy(cursor = info.cursor.copy(table = info.cursor.table + 1, row = 0))
      case info: RelationInfo => info.copy(cursor = info.cursor.copy(table = info.cursor.table + 1, row = 0))
    }
  }
  case class NodeInfo(dataResolver: DataResolver, models: List[(Model, Int)], cursor: Cursor) extends ExportInfo {
    val length: Int           = models.length
    val hasNext: Boolean      = cursor.table < length - 1
    lazy val current: Model   = models.find(_._2 == cursor.table).get._1
    lazy val nextModel: Model = models.find(_._2 == cursor.table + 1).get._1
  }

  case class ListInfo(dataResolver: DataResolver, models: List[(Model, Int)], cursor: Cursor) extends ExportInfo {
    val length: Int                                          = models.length
    val hasNext: Boolean                                     = cursor.table < length - 1
    val hasNextField: Boolean                                = cursor.field < fieldLength - 1
    lazy val currentModel: Model                             = models.find(_._2 == cursor.table).get._1
    lazy val nextModel: Model                                = models.find(_._2 == cursor.table + 1).get._1
    lazy val listFields: List[(String, TypeIdentifier, Int)] = currentModel.scalarListFields.zipWithIndex.map { case (f, i) => (f.name, f.typeIdentifier, i) }
    lazy val fieldLength: Int                                = listFields.length
    lazy val currentField: String                            = listFields.find(_._3 == cursor.field).get._1
    lazy val nextField: String                               = listFields.find(_._3 == cursor.field + 1).get._1
    lazy val currentTypeIdentifier: TypeIdentifier           = listFields.find(_._3 == cursor.field).get._2
    def arrayPlus(increase: Int): ListInfo                   = this.copy(cursor = this.cursor.copy(array = this.cursor.array + increase))
    def cursorAtNextField: ListInfo                          = this.copy(cursor = this.cursor.copy(field = this.cursor.field + 1, array = 0))
  }

  case class RelationInfo(dataResolver: DataResolver, relations: List[(RelationData, Int)], cursor: Cursor) extends ExportInfo {
    val length: Int                     = relations.length
    val hasNext: Boolean                = cursor.table < length - 1
    lazy val current: RelationData      = relations.find(_._2 == cursor.table).get._1
    lazy val nextRelation: RelationData = relations.find(_._2 == cursor.table + 1).get._1
  }

  case class RelationData(relationId: String, leftModel: String, leftField: String, rightModel: String, rightField: String)

  def toRelationData(r: Relation, project: Project): RelationData = {
    RelationData(r.id, r.getModelB_!(project).name, r.getModelBField_!(project).name, r.getModelA_!(project).name, r.getModelAField_!(project).name)
  }

  case class DataItemsPage(items: Seq[DataItem], hasMore: Boolean) { def itemCount: Int = items.length }

  object MyJsonProtocol extends DefaultJsonProtocol {

    implicit object AnyJsonFormat extends JsonFormat[Any] {
      def write(x: Any): JsValue = x match {
        case m: Map[_, _]   => JsObject(m.asInstanceOf[Map[String, Any]].mapValues(write))
        case l: List[Any]   => JsArray(l.map(write).toVector)
        case l: Vector[Any] => JsArray(l.map(write))
        case l: Seq[Any]    => JsArray(l.map(write).toVector)
        case n: Int         => JsNumber(n)
        case n: Long        => JsNumber(n)
        case n: BigDecimal  => JsNumber(n)
        case n: Double      => JsNumber(n)
        case s: String      => JsString(s)
        case true           => JsTrue
        case false          => JsFalse
        case v: JsValue     => v
        case null           => JsNull
        case r              => JsString(r.toString)
      }

      def read(x: JsValue): Any = {
        x match {
          case l: JsArray   => l.elements.map(read).toList
          case m: JsObject  => m.fields.mapValues(read)
          case s: JsString  => s.value
          case n: JsNumber  => n.value
          case b: JsBoolean => b.value
          case JsNull       => null
          case _            => sys.error("implement all scalar types!")
        }
      }
    }

    implicit val jsonBundle: RootJsonFormat[JsonBundle]                 = jsonFormat2(JsonBundle)
    implicit val importBundle: RootJsonFormat[ImportBundle]             = jsonFormat2(ImportBundle)
    implicit val importIdentifier: RootJsonFormat[ImportIdentifier]     = jsonFormat2(ImportIdentifier)
    implicit val importRelationSide: RootJsonFormat[ImportRelationSide] = jsonFormat2(ImportRelationSide)
    implicit val importNodeValue: RootJsonFormat[ImportNode]            = jsonFormat2(ImportNode)
    implicit val importListValue: RootJsonFormat[ImportList]            = jsonFormat2(ImportList)
    implicit val importRelation: RootJsonFormat[ImportRelation]         = jsonFormat2(ImportRelation)
    implicit val cursor: RootJsonFormat[Cursor]                         = jsonFormat4(Cursor)
    implicit val exportRequest: RootJsonFormat[ExportRequest]           = jsonFormat2(ExportRequest)
    implicit val resultFormat: RootJsonFormat[ResultFormat]             = jsonFormat3(ResultFormat)
  }

}
