
Compile / resourceGenerators +=  Def.task {
  val dir = (sourceManaged in Compile).value
  val projectName = name.value
  val projectGroup = organization.value
  val projectPage=homepage.value.map(_.toString).getOrElse("https://github.com/dacr")
  val projectVersion = version.value
  val buildDateTime = java.time.Instant.now().toString
  val buildUUID = java.util.UUID.randomUUID.toString
  val file = dir / "counters-meta.conf"
  IO.write(file,
    s"""counters {
       |  meta-info {
       |    project-name = "$projectName"
       |    project-group = "$projectGroup"
       |    project-page = "$projectPage"
       |    build-version = "$projectVersion"
       |    build-date-time = "$buildDateTime"
       |    build-uuid = "$buildUUID"
       |    contact-email = "crosson.david@gmail.com"
       |  }
       |}""".stripMargin
  )
  Seq(file)
}.taskValue
