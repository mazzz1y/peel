package wtf.mazy.peel.model

internal class OrderAllocator(private val state: DataState) {

    private val webAppOrders = mutableMapOf<String?, Int>()
    private var groupOrder: Int? = null

    fun nextWebAppOrder(groupUuid: String?): Int {
        val next = webAppOrders.getOrPut(groupUuid) {
            state.websites
                .filter { it.groupUuid == groupUuid }
                .maxOfOrNull { it.order }
                ?.plus(1) ?: 0
        }
        webAppOrders[groupUuid] = next + 1
        return next
    }

    fun nextGroupOrder(): Int {
        val next = groupOrder ?: (state.groups.maxOfOrNull { it.order }?.plus(1) ?: 0)
        groupOrder = next + 1
        return next
    }
}
