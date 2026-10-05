package com.singularity.todo.feature.genui.render.material3

import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.domain.registerDomainComponents
import com.singularity.todo.feature.genui.render.material3.atoms.registerBadge
import com.singularity.todo.feature.genui.render.material3.atoms.registerHeading
import com.singularity.todo.feature.genui.render.material3.atoms.registerIcon
import com.singularity.todo.feature.genui.render.material3.atoms.registerModal
import com.singularity.todo.feature.genui.render.material3.atoms.registerText
import com.singularity.todo.feature.genui.render.material3.input.registerButton
import com.singularity.todo.feature.genui.render.material3.input.registerCheckbox
import com.singularity.todo.feature.genui.render.material3.input.registerTabs
import com.singularity.todo.feature.genui.render.material3.input.registerTextField
import com.singularity.todo.feature.genui.render.material3.layout.registerCard
import com.singularity.todo.feature.genui.render.material3.layout.registerColumn
import com.singularity.todo.feature.genui.render.material3.layout.registerDivider
import com.singularity.todo.feature.genui.render.material3.layout.registerList
import com.singularity.todo.feature.genui.render.material3.layout.registerRow

/**
 * Installs every Material3 renderer into [registry].
 *
 * Renderers live in dedicated files under `atoms/`, `layout/`, and `input/`
 * — this object only orchestrates registration. The individual `register*()`
 * functions are `internal` to the material3 package and are not meant to be
 * called directly by application code.
 *
 * Usage:
 * ```
 * val registry = ComponentRegistry().also { Material3Catalog.installAll(it) }
 * ```
 */
object Material3Catalog {
    fun install(registry: ComponentRegistry) = registry.apply {
        registerText()
        registerHeading()
        registerButton()
        registerColumn()
        registerRow()
        registerCard()
        registerList()
        registerDivider()
        registerBadge()
        registerTextField()
        registerCheckbox()
        registerTabs()
        registerIcon()
        registerModal()
    }

    /**
     * Everything the client can draw: the Material3 set plus the domain components.
     *
     * The domain renderers live in their own package but are installed from here, because "what can
     * this client render" is one question and it has one answer. An installation that covered only
     * the Material3 half would leave a surface half-drawn with no error anywhere.
     */
    fun installAll(registry: ComponentRegistry) = registry.apply {
        install(registry)
        registerDomainComponents()
    }
}
