package io.jschneider.graphql.gremlin
import io.jschneider.graphql.gremlin.entity.GraphQLEntitySelectStep
import io.jschneider.graphql.gremlin.entity.GraphQLRelationEntity
import io.jschneider.graphql.gremlin.field.GraphQLField
import io.jschneider.graphql.gremlin.variable.MapVariableResolver
import io.jschneider.graphql.gremlin.variable.VariableResolver
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__
import org.apache.tinkerpop.gremlin.structure.Graph
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerFactory
import spock.lang.Specification

class GraphQLToGremlinCompilerSpec extends Specification {
    static Graph g
    static VariableResolver resolver = new MapVariableResolver([:])

    def setupSpec() {
        GraphTraversal.metaClass.entitySelect << { Map<String, String> fieldMappings,
                                                   Map<String, String> entityMappings = [:],
                                                   Map<String, String> queryAliases = [:] ->
            def entity = new GraphQLRelationEntity('', '')
            entity.fields.addAll(fieldMappings.entrySet().collect { new GraphQLField(it.key, it.value, queryAliases[it.key]) })
            entity.childEntities.addAll(entityMappings.entrySet().collect { new GraphQLRelationEntity(it.key, it.value) })
            delegate.asAdmin().addStep(new GraphQLEntitySelectStep<>(delegate.asAdmin(), entity))
        }

        g = TinkerFactory.createModern()
    }

    def 'match a vertex based on a property'() {
        when:
        def actual = GraphQLToGremlinCompiler.convertToGremlinTraversal(g, resolver, """
            {
              person(name: "marko") {
                name,
                age
              }
            }
        """)

        def expected = g.traversal().V()
            .match(
                __.as('query')
                    .match(
                        __.as('query').barrier(1).has('name', 'marko').as('__person0'),
                        __.as('__person0').values('name').as('name0'),
                        __.as('__person0').values('age').as('age0')
                    )
                    .entitySelect(name: 'name0', age: 'age0')
                    .as('person0')
            )
            .entitySelect([:], [person: 'person0'])

        then:
        expected == actual
        actual.next() == [person: [name: 'marko', age: 29]]
    }

    def 'match a relationship based on an in-vertex property'() {
        when:
        def actual = GraphQLToGremlinCompiler.convertToGremlinTraversal(g, resolver, """
            {
              person(name: "marko") {
                name,
                created(name: "lop") {
                  lang
                }
              }
            }
        """)

        def expected = g.traversal().V()
            .match(
                __.as('query')
                    .match(
                        __.as('query').barrier(1).has('name', 'marko').as('__person0'),
                        __.as('__person0').values('name').as('name0'),
                        __.as('__person0')
                            .match(
                                __.as('__person0').union(
                                        __.outE('created').has('name', 'lop').inV(),
                                        __.out('created').has('name', 'lop')
                                    )
                                    .dedup()
                                    .as('__created0'),
                                __.as('__created0').values('lang').as('lang0')
                            )
                            .entitySelect([lang: 'lang0'])
                            .as('created0')
                    )
                    .entitySelect([name: 'name0'], [created: 'created0'])
                    .as('person0')
            )
            .entitySelect([:], [person: 'person0'])

        then:
        expected == actual
        actual.next() == [person: [name: 'marko', created: [lang: 'java']]]
    }

    def 'nested properties with the same name'() {
        when:
        def actual = GraphQLToGremlinCompiler.convertToGremlinTraversal(g, resolver, """
            {
              person(name: "marko") {
                name,
                created(lang: "java") {
                  name
                }
              }
            }
        """)

        def expected = g.traversal().V()
            .match(
                __.as('query')
                    .match(
                        __.as('query').barrier(1).has('name', 'marko').as('__person0'),
                        __.as('__person0').values('name').as('name0'),
                        __.as('__person0')
                            .match(
                                __.as('__person0').union(
                                        __.outE('created').has('lang', 'java').inV(),
                                        __.out('created').has('lang', 'java')
                                    )
                                    .dedup()
                                    .as('__created0'),
                                __.as('__created0').values('name').as('name1')
                            )
                            .entitySelect([name: 'name1'])
                            .as('created0')
                    )
                    .entitySelect([name: 'name0'], ['created': 'created0'])
                    .as('person0')
            )
            .entitySelect([:], [person: 'person0'])

        then:
        expected == actual
        actual.next() == [person: [name: 'marko', created: [name: 'lop']]]
    }

    def 'match a relationship based on an edge property'() {
        when:
        def actual = GraphQLToGremlinCompiler.convertToGremlinTraversal(g, resolver, """
            {
              person(name: "marko") {
                name,
                created(weight: 0.4) {
                  lang
                }
              }
            }
        """)

        then:
        actual.next() == [person: [name: 'marko', created: [lang: 'java']]]
    }

    def 'aliased fields'() {
        when:
        def actual = GraphQLToGremlinCompiler.convertToGremlinTraversal(g, resolver, """
            {
              person(name: "marko") {
                nameAlias: name,
                age
              }
            }
        """)

        then:
        actual.next() == [person: [nameAlias: 'marko', age: 29]]
    }

    def 'query with no fragments'() {
        when:
        def actual = GraphQLToGremlinCompiler.convertToGremlinTraversal(g, resolver, """
            query noFragments {
              person(name: "marko") {
                name,
                age
              }
            }
        """)

        then:
        actual.next() == [person: [name: 'marko', age: 29]]
    }

    def 'mutation is not supported'() {
        when:
        GraphQLToGremlinCompiler.convertToGremlinTraversal(g, resolver, 'mutation { }')

        then:
        thrown(UnsupportedOperationException)
    }

    def 'query with fragments'() {
        when:
        def actual = GraphQLToGremlinCompiler.convertToGremlinTraversal(g, resolver, """
            query withFragments {
              person(name: "marko") {
                ...personFields
              }
            }

            fragment personFields on Person {
              name,
              age
            }
        """)

        then:
        actual.next() == [person: [name: 'marko', age: 29]]
    }

    def 'query with nested fragments'() {
        when:
        def actual = GraphQLToGremlinCompiler.convertToGremlinTraversal(g, resolver, """
            query withFragments {
              person(name: "marko") {
                ...personFields
              }
            }

            fragment personFields on Person {
              ...nestedPersonFields
            }

            fragment nestedPersonFields on Person {
              name,
              age
            }
        """)

        then:
        actual.next() == [person: [name: 'marko', age: 29]]
    }

    def 'query with inline fragments'() {
        when:
        def actual = GraphQLToGremlinCompiler.convertToGremlinTraversal(g, resolver, """{
          person(name: "marko") {
            ... on Person {
              name,
              age
            }
          }
        }""")

        then:
        actual.next() == [person: [name: 'marko', age: 29]]
    }

    def 'query with variable input'() {
        when:
        def actual = GraphQLToGremlinCompiler.convertToGremlinTraversal(g, new MapVariableResolver([name: 'marko']), """{
          person(name: \$name) {
              age
          }
        }""")

        then:
        actual.next() == [person: [age: 29]]
    }

    def 'query with variable input with default value'() {
        when:
        def actual = GraphQLToGremlinCompiler.convertToGremlinTraversal(g, resolver, """
          query findMarko (\$name: String = "marko") {
              person(name: \$name) {
                  age
              }
          }
        """)

        then:
        actual.next() == [person: [age: 29]]
    }

    def 'query with skip/include directive on a field'() {
        when:
        def actual = GraphQLToGremlinCompiler.convertToGremlinTraversal(g, resolver, """
          query findMarko (\$applied: Boolean = $applied) {
              person(name: "marko") {
                  age @$directive(if: \$applied)
              }
          }
        """)

        then:
        actual.next() == result

        where:
        directive   |   applied     | result
        'skip'      |   true        | [person: [:]]
        'skip'      |   false       | [person: [age: 29]]
        'include'   |   false       | [person: [:]]
        'include'   |   true        | [person: [age: 29]]
    }

    def 'query with skip directive on an entity'() {
        when:
        def actual = GraphQLToGremlinCompiler.convertToGremlinTraversal(g, resolver, """
          query findMarko (\$applied: Boolean = $applied) {
              person(name: "marko") {
                created(name: "lop") @$directive(if: \$applied) {
                  lang
                }
              }
          }
        """)

        then:
        actual.next() == result

        where:
        directive   |   applied     | result
        'skip'      |   true        | [person: [:]]
        'skip'      |   false       | [person: [created: [lang: 'java']]]
        'include'   |   false       | [person: [:]]
        'include'   |   true        | [person: [created: [lang: 'java']]]
    }

    def 'query with skip directive on inline fragment'() {
        when:
        def actual = GraphQLToGremlinCompiler.convertToGremlinTraversal(g, resolver, """
          query findMarko (\$applied: Boolean = $applied) {
              person(name: "marko") {
                ... on Language @$directive(if: \$applied) {
                  created(name: "lop") {
                    lang
                  }
                }
              }
          }
        """)

        then:
        actual.next() == result

        where:
        directive   |   applied     | result
        'skip'      |   true        | [person: [:]]
        'skip'      |   false       | [person: [created: [lang: 'java']]]
        'include'   |   false       | [person: [:]]
        'include'   |   true        | [person: [created: [lang: 'java']]]
    }

    def 'query with skip directive on fragment spread'() {
        when:
        def actual = GraphQLToGremlinCompiler.convertToGremlinTraversal(g, resolver, """
          query findMarko (\$applied: Boolean = $applied) {
              person(name: "marko") {
                ...preferredLanguage @$directive(if: \$applied)
              }
          }

          fragment preferredLanguage on Language {
            created(name: "lop") {
              lang
            }
          }
        """)

        then:
        actual.next() == result

        where:
        directive   |   applied     | result
        'skip'      |   true        | [person: [:]]
        'skip'      |   false       | [person: [created: [lang: 'java']]]
        'include'   |   false       | [person: [:]]
        'include'   |   true        | [person: [created: [lang: 'java']]]
    }

    def 'query with directive on fragment definition'() {
        when:
        def actual = GraphQLToGremlinCompiler.convertToGremlinTraversal(g, resolver, """
          query findMarko(\$applied: Boolean = $applied) {
              person(name: "marko") {
                ...preferredLanguage
              }
          }

          fragment preferredLanguage on Language @$directive(if: \$applied) {
            created(name: "lop") {
              lang
            }
          }
        """)

        then:
        actual.next() == result

        where:
        directive   |   applied     | result
        'skip'      |   true        | [person: [:]]
        'skip'      |   false       | [person: [created: [lang: 'java']]]
        'include'   |   false       | [person: [:]]
        'include'   |   true        | [person: [created: [lang: 'java']]]
    }
}
